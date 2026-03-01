package com.ecobrain.plugin.command;

import com.ecobrain.plugin.EcoBrainPlugin;
import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.EconomyService;
import com.ecobrain.plugin.service.MarketService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /ecobrain 主命令：
 * - sell <amount>
 * - buy <amount>
 * - bulk
 * - market [page]
 * - admin ...
 */
public class EcoBrainCommand implements CommandExecutor, TabCompleter {
    private final JavaPlugin plugin;
    private final ItemSerializer itemSerializer;
    private final ItemMarketRepository repository;
    private final MarketService marketService;
    private final EconomyService economyService;
    private final BulkSellGUI bulkSellGUI;
    private final MarketViewGUI marketViewGUI;
    private final AdminCommand adminCommand;
    private volatile long cooldownMs;
    private final ConcurrentHashMap<String, Long> playerActionLock = new ConcurrentHashMap<>();

    public EcoBrainCommand(JavaPlugin plugin, ItemSerializer itemSerializer, ItemMarketRepository repository,
                           MarketService marketService, EconomyService economyService, BulkSellGUI bulkSellGUI,
                           MarketViewGUI marketViewGUI, AdminCommand adminCommand, long cooldownMs) {
        this.plugin = plugin;
        this.itemSerializer = itemSerializer;
        this.repository = repository;
        this.marketService = marketService;
        this.economyService = economyService;
        this.bulkSellGUI = bulkSellGUI;
        this.marketViewGUI = marketViewGUI;
        this.adminCommand = adminCommand;
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /ecobrain <sell|buy|bulk|market|reload|admin>");
            return true;
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            return handleReload(sender);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if (!economyService.isReady()) {
            sender.sendMessage(ChatColor.RED + "Vault 经济未就绪，请联系管理员。");
            return true;
        }
        return switch (args[0].toLowerCase()) {
            case "sell" -> handleSell(player, args);
            case "buy" -> handleBuy(player, args);
            case "bulk" -> {
                bulkSellGUI.open(player);
                yield true;
            }
            case "market" -> handleMarket(player, args);
            case "admin" -> adminCommand.handle(sender, args);
            default -> {
                player.sendMessage(ChatColor.YELLOW + "未知子命令。");
                yield true;
            }
        };
    }

    /**
     * 卖出流程（主手物品）：
     * 1) 主线程做最小参数与背包校验
     * 2) 异步进行序列化、哈希、IPO 冷启动、滑点积分计算
     * 3) 回到主线程做二次校验后扣物品+发钱
     * 4) 再次异步持久化库存变更与成交记录
     */
    private boolean handleSell(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "用法: /ecobrain sell <数量>");
            return true;
        }
        int amount = parsePositiveInt(args[1]);
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "数量必须是正整数。");
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR || hand.getAmount() < amount) {
            player.sendMessage(ChatColor.RED + "主手物品数量不足。");
            return true;
        }

        if (!tryAcquireLock(player)) {
            player.sendMessage(ChatColor.RED + "你操作太快了，请稍后再试。");
            return true;
        }
        ItemStack snapshot = hand.clone();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String base64 = itemSerializer.serializeToBase64(snapshot.asOne());
                String hash = itemSerializer.sha256(base64);
                ItemMarketRecord record = marketService.ensureIpoAsync(hash, base64, amount).join();
                MarketService.TradeQuote quote = marketService.quoteSell(record, amount);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        ItemStack latest = player.getInventory().getItemInMainHand();
                        if (latest.getType() == Material.AIR || latest.getAmount() < amount || !latest.isSimilar(snapshot)) {
                            player.sendMessage(ChatColor.RED + "你的主手物品已变化，交易已取消。");
                            releaseLock(player);
                            return;
                        }
                        latest.setAmount(latest.getAmount() - amount);
                        player.getInventory().setItemInMainHand(latest.getAmount() <= 0 ? new ItemStack(Material.AIR) : latest);
                        if (!economyService.deposit(player, quote.totalPrice())) {
                            latest.setAmount(latest.getAmount() + amount);
                            player.getInventory().setItemInMainHand(latest);
                            player.sendMessage(ChatColor.RED + "发放金币失败，交易已回滚。");
                            releaseLock(player);
                            return;
                        }

                        player.sendMessage(ChatColor.GREEN + "出售成功，获得 " + String.format("%.2f", quote.totalPrice()) + " 金币。");
                        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> marketService.settle(hash, quote, amount));
                    } finally {
                        releaseLock(player);
                    }
                });
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "出售失败: " + e.getMessage());
                releaseLock(player);
            }
        });
        return true;
    }

    /**
     * 买入流程（根据主手物品模板识别市场品类）：
     * 1) 异步读取数据库并计算买入滑点总价
     * 2) 主线程扣钱并发放反序列化物品
     * 3) 异步更新库存和成交统计
     */
    private boolean handleBuy(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.YELLOW + "用法: /ecobrain buy <数量>");
            return true;
        }
        int amount = parsePositiveInt(args[1]);
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "数量必须是正整数。");
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + "请先手持一个目标物品作为购买模板。");
            return true;
        }

        if (!tryAcquireLock(player)) {
            player.sendMessage(ChatColor.RED + "你操作太快了，请稍后再试。");
            return true;
        }
        ItemStack snapshot = hand.clone();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String base64 = itemSerializer.serializeToBase64(snapshot.asOne());
                String hash = itemSerializer.sha256(base64);
                Optional<ItemMarketRecord> optionalRecord = repository.findByHash(hash);
                if (optionalRecord.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "市场没有收录该物品，请先由玩家卖出触发 IPO。");
                    releaseLock(player);
                    return;
                }
                ItemMarketRecord record = optionalRecord.get();
                if (record.getCurrentInventory() <= amount) {
                    player.sendMessage(ChatColor.RED + "系统库存不足。当前库存: " + record.getCurrentInventory());
                    releaseLock(player);
                    return;
                }
                MarketService.TradeQuote quote = marketService.quoteBuy(record, amount);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (economyService.getBalance(player) < quote.totalPrice()) {
                            player.sendMessage(ChatColor.RED + "金币不足，需要 " + String.format("%.2f", quote.totalPrice()));
                            releaseLock(player);
                            return;
                        }
                        if (!economyService.withdraw(player, quote.totalPrice())) {
                            player.sendMessage(ChatColor.RED + "扣款失败，交易取消。");
                            releaseLock(player);
                            return;
                        }
                        ItemStack item = itemSerializer.deserializeFromBase64(record.getItemBase64());
                        item.setAmount(Math.min(item.getMaxStackSize(), amount));
                        int rest = amount;
                        while (rest > 0) {
                            ItemStack part = item.clone();
                            int stack = Math.min(part.getMaxStackSize(), rest);
                            part.setAmount(stack);
                            player.getInventory().addItem(part);
                            rest -= stack;
                        }
                        player.sendMessage(ChatColor.GREEN + "购买成功，花费 " + String.format("%.2f", quote.totalPrice()) + " 金币。");
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> marketService.settle(hash, quote, amount));
                    } finally {
                        releaseLock(player);
                    }
                });
            } catch (Exception e) {
                player.sendMessage(ChatColor.RED + "购买失败: " + e.getMessage());
                releaseLock(player);
            }
        });
        return true;
    }

    private boolean handleMarket(Player player, String[] args) {
        int page = args.length >= 2 ? Math.max(1, parsePositiveInt(args[1])) : 1;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ItemMarketRecord> records = repository.findAll();
            Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, records, page));
        });
        return true;
    }

    private int parsePositiveInt(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 主命令热更新入口：重新加载 config.yml 并下发到运行时组件。
     */
    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("ecobrain.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行重载。");
            return true;
        }
        if (!(plugin instanceof EcoBrainPlugin ecoBrainPlugin)) {
            sender.sendMessage(ChatColor.RED + "插件实例异常，无法重载。");
            return true;
        }
        try {
            ecoBrainPlugin.reloadRuntimeSettings();
            sender.sendMessage(ChatColor.GREEN + "EcoBrain 配置已热更新。");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "热更新失败: " + e.getMessage());
        }
        return true;
    }

    public void updateCooldown(long cooldownMs) {
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    private boolean tryAcquireLock(Player player) {
        long now = System.currentTimeMillis();
        Long old = playerActionLock.put(player.getUniqueId().toString(), now);
        return old == null || now - old > cooldownMs;
    }

    private void releaseLock(Player player) {
        playerActionLock.remove(player.getUniqueId().toString());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("sell", "buy", "bulk", "market", "reload", "admin");
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])) {
            return List.of("clear", "freeze", "unfreeze");
        }
        return new ArrayList<>();
    }
}
