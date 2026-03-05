package com.ecobrain.plugin.command;

import com.ecobrain.plugin.EcoBrainPlugin;
import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.rewards.RewardsGUI;
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
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.ecobrain.plugin.persistence.ItemMarketRepository.moneyToCents;

/**
 * /ecobrain 主命令：
 * - sell <amount>
 * - buy <amount>
 * - bulk
 * - market [page]
 * - admin ...
 */
public class EcoBrainCommand implements CommandExecutor, TabCompleter {
    private enum SellMode {
        MAIN_HAND_ONLY,
        INVENTORY_ALL_SIMILAR
    }

    private final JavaPlugin plugin;
    private final ItemSerializer itemSerializer;
    private final ItemMarketRepository repository;
    private final MarketService marketService;
    private final EconomyService economyService;
    private final BulkSellGUI bulkSellGUI;
    private final MarketViewGUI marketViewGUI;
    private final RewardsGUI rewardsGUI;
    private final AdminCommand adminCommand;
    private volatile long cooldownMs;
    private final ConcurrentHashMap<String, Long> playerActionLock = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> playerLastActionAt = new ConcurrentHashMap<>();

    public EcoBrainCommand(JavaPlugin plugin, ItemSerializer itemSerializer, ItemMarketRepository repository,
                           MarketService marketService, EconomyService economyService, BulkSellGUI bulkSellGUI,
                           MarketViewGUI marketViewGUI, RewardsGUI rewardsGUI, AdminCommand adminCommand, long cooldownMs) {
        this.plugin = plugin;
        this.itemSerializer = itemSerializer;
        this.repository = repository;
        this.marketService = marketService;
        this.economyService = economyService;
        this.bulkSellGUI = bulkSellGUI;
        this.marketViewGUI = marketViewGUI;
        this.rewardsGUI = rewardsGUI;
        this.adminCommand = adminCommand;
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.YELLOW + "用法: /ecobrain <sell|buy|market|reload|admin>");
                return true;
            }
            return handleMarket(player, new String[] {"market", "1"});
        }
        if ("reload".equalsIgnoreCase(args[0])) {
            return handleReload(sender);
        }
        if ("admin".equalsIgnoreCase(args[0])) {
            return adminCommand.handle(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        if ("rewards".equalsIgnoreCase(args[0]) || "reward".equalsIgnoreCase(args[0])) {
            if (!player.hasPermission("ecobrain.rewards")) {
                player.sendMessage(ChatColor.RED + "你没有权限打开奖励菜单。");
                return true;
            }
            rewardsGUI.open(player);
            return true;
        }
        if (!economyService.isReady()) {
            sender.sendMessage(ChatColor.RED + "Vault 经济未就绪，请联系管理员。");
            return true;
        }

        // Compatibility: allow `/ecobrain sell buy <amount>` to route to `/ecobrain buy <amount>`
        // so the hand hint copy can stay immersive while the actual buy logic remains unchanged.
        if (args.length >= 2 && "sell".equalsIgnoreCase(args[0]) && "buy".equalsIgnoreCase(args[1])) {
            String[] buyArgs;
            if (args.length >= 3) {
                buyArgs = new String[] {"buy", args[2]};
            } else {
                buyArgs = new String[] {"buy"};
            }
            return handleBuy(player, buyArgs);
        }

        return switch (args[0].toLowerCase()) {
            case "sell" -> handleSell(player, args);
            case "buy" -> handleBuy(player, args);
            case "market" -> handleMarket(player, args);
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
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() == Material.AIR) {
            player.sendMessage(ChatColor.RED + "请先手持要出售的物品。");
            return true;
        }
        ItemStack template = hand.clone();

        if (template.getMaxStackSize() == 1) {
            player.sendMessage(ChatColor.RED + "系统市场只回收可堆叠的材料，不可出售不可堆叠的物品！");
            return true;
        }

        SellMode mode = SellMode.MAIN_HAND_ONLY;
        int amount;
        if (args.length < 2) {
            // /ecobrain sell -> 默认卖主手整组
            amount = hand.getAmount();
        } else if ("all".equalsIgnoreCase(args[1])) {
            // /ecobrain sell all -> 卖背包内所有与主手同 NBT 的物品
            mode = SellMode.INVENTORY_ALL_SIMILAR;
            amount = countSimilarInStorage(player.getInventory(), template);
        } else {
            amount = parsePositiveInt(args[1]);
            if (amount <= 0 || amount > 10000) {
                player.sendMessage(ChatColor.RED + "数量必须是 1~10000 之间的正整数，或使用 /ecobrain sell all。");
                return true;
            }
        }
        if (amount <= 0) {
            player.sendMessage(ChatColor.RED + "没有可出售的同类物品。");
            return true;
        }
        if (mode == SellMode.MAIN_HAND_ONLY && hand.getAmount() < amount) {
            player.sendMessage(ChatColor.RED + "主手物品数量不足。");
            return true;
        }

        if (!tryAcquireLock(player)) {
            player.sendMessage(ChatColor.RED + "你操作太快了，请稍后再试。");
            return true;
        }
        int finalAmount = amount;
        SellMode finalMode = mode;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String base64 = itemSerializer.serializeToBase64(template.asOne());
                String hash = itemSerializer.sha256(base64);
                MarketService.IpoState ipoState = marketService.ensureIpoForSellAsync(hash, base64, finalAmount).join();
                ItemMarketRecord record = ipoState.record();
                MarketService.TradeQuote quote = marketService.quoteSell(record, finalAmount);
                long payoutCents = moneyToCents(quote.totalPrice());
                boolean reservedMoney = repository.tryReserveTreasuryCents(payoutCents);
                if (!reservedMoney) {
                    // rollback IPO record that was created only for this failed sell attempt
                    if (ipoState.createdNow()) {
                        try {
                            repository.deleteByHash(hash);
                        } catch (Exception ignored) {
                        }
                    }
                    sendMessageSync(player, ChatColor.RED + "系统金库不足，暂无法收购该物品。请稍后再试。");
                    releaseLock(player);
                    return;
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        PlayerInventory inventory = player.getInventory();
                        ItemStack[] storageBefore = cloneStorage(inventory.getStorageContents());

                        if (finalMode == SellMode.MAIN_HAND_ONLY) {
                            ItemStack latest = inventory.getItemInMainHand();
                            if (latest.getType() == Material.AIR || latest.getAmount() < finalAmount || !latest.isSimilar(template)) {
                                player.sendMessage(ChatColor.RED + "你的主手物品已变化，交易已取消。");
                                long cents = payoutCents;
                                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                    repository.releaseTreasuryCents(cents);
                                    if (ipoState.createdNow()) {
                                        try {
                                            repository.deleteByHash(hash);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                                releaseLock(player);
                                return;
                            }
                            latest.setAmount(latest.getAmount() - finalAmount);
                            inventory.setItemInMainHand(latest.getAmount() <= 0 ? new ItemStack(Material.AIR) : latest);
                        } else {
                            int available = countSimilarInStorage(inventory, template);
                            if (available < finalAmount) {
                                player.sendMessage(ChatColor.RED + "背包同类物品不足，交易已取消。");
                                long cents = payoutCents;
                                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                    repository.releaseTreasuryCents(cents);
                                    if (ipoState.createdNow()) {
                                        try {
                                            repository.deleteByHash(hash);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                });
                                releaseLock(player);
                                return;
                            }
                            removeSimilarFromStorage(inventory, template, finalAmount);
                        }

                        if (!economyService.deposit(player, quote.totalPrice())) {
                            inventory.setStorageContents(storageBefore);
                            player.sendMessage(ChatColor.RED + "发放金币失败，交易已回滚。");
                            long cents = payoutCents;
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                                repository.releaseTreasuryCents(cents);
                                if (ipoState.createdNow()) {
                                    try {
                                        repository.deleteByHash(hash);
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                            releaseLock(player);
                            return;
                        }

                        player.sendMessage(ChatColor.GREEN + "出售成功，共出售 " + finalAmount + " 个，获得 "
                            + String.format("%.2f", quote.totalPrice()) + " 金币。");
                        Bukkit.getScheduler().runTaskAsynchronously(plugin,
                            () -> marketService.settleSell(player, hash, record, quote, finalAmount, ipoState.createdNow()));
                    } finally {
                        releaseLock(player);
                    }
                });
            } catch (Exception e) {
                sendMessageSync(player, ChatColor.RED + "出售失败: " + e.getMessage());
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
        if (amount <= 0 || amount > 10000) {
            player.sendMessage(ChatColor.RED + "单次购买数量必须在 1 ~ 10000 之间。");
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
            boolean reserved = false;
            String hash = null;
            try {
                String base64 = itemSerializer.serializeToBase64(snapshot.asOne());
                hash = itemSerializer.sha256(base64);
                Optional<ItemMarketRecord> optionalRecord = repository.findByHash(hash);
                if (optionalRecord.isEmpty()) {
                    sendMessageSync(player, ChatColor.RED + "市场没有收录该物品，请先由玩家卖出触发 IPO。");
                    releaseLock(player);
                    return;
                }
                ItemMarketRecord record = optionalRecord.get();
                int requestedAmount = amount;
                int currentPhysical = record.getPhysicalStock();
                int criticalLimit = plugin.getConfig().getInt("circuit-breaker.critical-inventory", 2);

                // 1. 如果当前库存【已经】触发熔断，直接一票否决
                if (currentPhysical <= criticalLimit) {
                    sendMessageSync(player, ChatColor.RED + "系统展柜保护机制：该物品库存已达红线，暂停出售！");
                    releaseLock(player);
                    return;
                }

                // 2. 如果购买后，会导致库存跌破熔断线（穿仓）
                if (currentPhysical - requestedAmount < criticalLimit) {
                    int maxCanBuy = currentPhysical - criticalLimit; // 计算出最多能买多少个
                    sendMessageSync(player, ChatColor.RED + "购买失败！系统最多只能再向您出售 " + maxCanBuy + " 个该物品。");
                    releaseLock(player);
                    return;
                }
                MarketService.TradeQuote quote = marketService.quoteBuy(record, amount);

                // 3. 关键：先原子预留真实库存，防止并发超卖
                reserved = marketService.reservePhysicalStockForBuy(hash, amount);
                if (!reserved) {
                    sendMessageSync(player, ChatColor.RED + "购买失败：库存不足或触发熔断保护，请稍后重试。");
                    releaseLock(player);
                    return;
                }

                String finalHash = hash;
                int finalAmount = amount;
                ItemMarketRecord finalRecord = record;
                MarketService.TradeQuote finalQuote = quote;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (economyService.getBalance(player) < finalQuote.totalPrice()) {
                            player.sendMessage(ChatColor.RED + "金币不足，需要 " + String.format("%.2f", finalQuote.totalPrice()));
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> marketService.cancelReservedBuy(finalHash, finalAmount));
                            return;
                        }
                        if (!economyService.withdraw(player, finalQuote.totalPrice())) {
                            player.sendMessage(ChatColor.RED + "扣款失败，交易取消。");
                            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> marketService.cancelReservedBuy(finalHash, finalAmount));
                            return;
                        }
                        ItemStack item = itemSerializer.deserializeFromBase64(finalRecord.getItemBase64());
                        item.setAmount(Math.min(item.getMaxStackSize(), finalAmount));
                        int rest = finalAmount;
                        while (rest > 0) {
                            ItemStack part = item.clone();
                            int stack = Math.min(part.getMaxStackSize(), rest);
                            part.setAmount(stack);
                            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(part);
                            if (!leftover.isEmpty()) {
                                for (ItemStack drop : leftover.values()) {
                                    player.getWorld().dropItem(player.getLocation(), drop);
                                }
                            }
                            rest -= stack;
                        }
                        player.sendMessage(ChatColor.GREEN + "购买成功，花费 " + String.format("%.2f", finalQuote.totalPrice()) + " 金币。");
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> marketService.settleBuyAfterReservation(player, finalHash, finalRecord, finalQuote, finalAmount));
                    } finally {
                        releaseLock(player);
                    }
                });
            } catch (Exception e) {
                sendMessageSync(player, ChatColor.RED + "购买失败: " + e.getMessage());
                if (reserved && hash != null) {
                    String finalHash = hash;
                    int finalAmount = amount;
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> marketService.cancelReservedBuy(finalHash, finalAmount));
                }
                releaseLock(player);
            }
        });
        return true;
    }

    private boolean handleMarket(Player player, String[] args) {
        if (!tryAcquireLock(player)) {
            player.sendMessage(ChatColor.RED + "你操作太快了，请稍后再试。");
            return true;
        }
        int page = args.length >= 2 ? Math.max(1, parsePositiveInt(args[1])) : 1;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<ItemMarketRecord> records = repository.findAll();
                List<ItemMarketRecord> filtered = marketViewGUI.filterAndSort(records, player.getUniqueId());
                double treasury = ItemMarketRepository.centsToMoney(repository.getTreasuryBalanceCents());
                Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, filtered, page, treasury));
            } finally {
                releaseLock(player);
            }
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

    private int countSimilarInStorage(PlayerInventory inventory, ItemStack template) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() != Material.AIR && item.isSimilar(template)) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private void removeSimilarFromStorage(PlayerInventory inventory, ItemStack template, int amount) {
        int remaining = amount;
        ItemStack[] storage = inventory.getStorageContents();
        for (int i = 0; i < storage.length && remaining > 0; i++) {
            ItemStack item = storage[i];
            if (item == null || item.getType() == Material.AIR || !item.isSimilar(template)) {
                continue;
            }
            int take = Math.min(item.getAmount(), remaining);
            item.setAmount(item.getAmount() - take);
            remaining -= take;
            if (item.getAmount() <= 0) {
                storage[i] = null;
            } else {
                storage[i] = item;
            }
        }
        inventory.setStorageContents(storage);
    }

    private ItemStack[] cloneStorage(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
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
        String key = player.getUniqueId().toString();
        long now = System.currentTimeMillis();
        Long last = playerLastActionAt.get(key);
        if (last != null && cooldownMs > 0L && (now - last) < cooldownMs) {
            return false;
        }
        return playerActionLock.putIfAbsent(key, now) == null;
    }

    private void releaseLock(Player player) {
        String key = player.getUniqueId().toString();
        playerActionLock.remove(key);
        playerLastActionAt.put(key, System.currentTimeMillis());
    }

    private void sendMessageSync(Player player, String message) {
        if (player == null || message == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(message));
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return List.of("sell", "buy", "market", "rewards", "reload", "admin");
        }
        if (args.length == 2 && "sell".equalsIgnoreCase(args[0])) {
            return List.of("all", "buy", "1", "16", "64");
        }
        if (args.length == 3 && "sell".equalsIgnoreCase(args[0]) && "buy".equalsIgnoreCase(args[1])) {
            return List.of("1", "16", "64");
        }
        if (args.length == 2 && "admin".equalsIgnoreCase(args[0])) {
            return List.of("clear", "freeze", "unfreeze", "clearleaderboard", "settarget", "exportdata", "reclaimmoney");
        }
        if (args.length == 3 && "admin".equalsIgnoreCase(args[0]) && "unfreeze".equalsIgnoreCase(args[1])) {
            return List.of("all");
        }
        return new ArrayList<>();
    }
}
