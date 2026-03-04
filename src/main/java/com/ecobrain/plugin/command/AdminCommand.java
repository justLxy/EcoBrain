package com.ecobrain.plugin.command;

import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Locale;

/**
 * 管理命令执行器（/ecobrain admin ...）。
 */
public class AdminCommand {
    private final JavaPlugin plugin;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;
    private final EconomyService economyService;

    public AdminCommand(JavaPlugin plugin, ItemMarketRepository repository, ItemSerializer itemSerializer, EconomyService economyService) {
        this.plugin = plugin;
        this.repository = repository;
        this.itemSerializer = itemSerializer;
        this.economyService = economyService;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ecobrain.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行管理命令。");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "用法:");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin clear <hash>");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin freeze <hash>");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin unfreeze            (解冻主手物品)");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin unfreeze <hash>");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin unfreeze all        (解冻所有物品)");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin settarget <数量>      (修改主手物品的目标库存)");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin clearleaderboard");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin exportdata          (导出供 AI 训练的离线数据)");
            sender.sendMessage(ChatColor.YELLOW + "/ecobrain admin reclaimmoney [preview] (回收所有“系统净支出”的金币，使用 QuickTax，可扣成负数)");
            return true;
        }

        String action = args[1].toLowerCase();
        if ("exportdata".equals(action)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String fileName = repository.exportTransactionDataForTraining(plugin.getDataFolder());
                    sendMessageSync(sender, ChatColor.GREEN + "成功导出交易日志数据到: " + fileName);
                } catch (Exception e) {
                    sendMessageSync(sender, ChatColor.RED + "导出数据失败: " + e.getMessage());
                }
            });
            return true;
        }

        if ("clearleaderboard".equalsIgnoreCase(action) || "resetleaderboard".equalsIgnoreCase(action)
            || "clear-ranking".equalsIgnoreCase(action) || "clearranking".equalsIgnoreCase(action)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    int rows = repository.clearLeaderboard();
                    sendMessageSync(sender, ChatColor.GREEN + "已清空交易排行榜数据。" + (rows > 0 ? (" 删除行数=" + rows) : ""));
                } catch (Exception e) {
                    sendMessageSync(sender, ChatColor.RED + "执行失败: " + e.getMessage());
                }
            });
            return true;
        }

        if ("reclaimmoney".equalsIgnoreCase(action) || "reclaim".equalsIgnoreCase(action) || "reclaimsystemmoney".equalsIgnoreCase(action)) {
            boolean preview = args.length >= 3 && (
                "preview".equalsIgnoreCase(args[2]) || "dry".equalsIgnoreCase(args[2]) || "dryrun".equalsIgnoreCase(args[2])
            );

            // QuickTax 必须存在（用于 debt-mode 扣到负数）
            if (Bukkit.getPluginManager().getPlugin("QuickTax") == null) {
                sender.sendMessage(ChatColor.RED + "未检测到 QuickTax 插件，无法执行回收。");
                sender.sendMessage(ChatColor.YELLOW + "提示：QuickTax 命令通常为 /qt collectname <玩家> <金额>，并在 QuickTax 的 config.yml 里设置 debt-mode=2 才能扣成负数。");
                return true;
            }

            double minAmount = plugin.getConfig().getDouble("admin.reclaim.min-amount", 0.01D);
            int perTick = Math.max(1, plugin.getConfig().getInt("admin.reclaim.per-tick", 5));
            String cmdTemplate = plugin.getConfig().getString("admin.reclaim.quicktax-command", "qt collectname {player} {amount}");
            boolean notifyPlayer = plugin.getConfig().getBoolean("admin.reclaim.notify-player", true);
            String notifyMessageTemplate = plugin.getConfig().getString(
                "admin.reclaim.notify-message",
                "&e系统资金回收：已扣除 &f{amount}&e，当前余额 &f{balance}"
            );

            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    var list = repository.getOutstandingSystemMoneyByPlayer(minAmount);
                    if (list.isEmpty()) {
                        sendMessageSync(sender, ChatColor.GREEN + "没有需要回收的系统资金（所有玩家 outstanding <= " + formatMoney(minAmount) + "）。");
                        return;
                    }

                    // 统计汇总
                    double total = list.stream().mapToDouble(v -> v.outstandingMoney()).sum();
                    int players = list.size();

                    if (preview) {
                        list.sort(Comparator.comparingDouble((com.ecobrain.plugin.model.SystemMoneyOutstanding v) -> v.outstandingMoney()).reversed());
                        sendMessageSync(sender, ChatColor.YELLOW + "【预览】将回收玩家数=" + players + "，总额=" + formatMoney(total));
                        int show = Math.min(10, list.size());
                        for (int i = 0; i < show; i++) {
                            var v = list.get(i);
                            sendMessageSync(sender, ChatColor.GRAY + "" + (i + 1) + ". " + safeName(v.playerName(), v.playerUuid()) + " -> " + formatMoney(v.outstandingMoney()));
                        }
                        if (list.size() > show) {
                            sendMessageSync(sender, ChatColor.GRAY + "... 还有 " + (list.size() - show) + " 名玩家");
                        }
                        sendMessageSync(sender, ChatColor.YELLOW + "执行回收: /ecobrain admin reclaimmoney");
                        return;
                    }

                    // 主线程逐步派发命令，避免一次性跑太多 dispatch 卡顿
                    ArrayDeque<com.ecobrain.plugin.model.SystemMoneyOutstanding> queue = new ArrayDeque<>(list);
                    sendMessageSync(sender, ChatColor.YELLOW + "开始回收系统资金：玩家数=" + players + "，总额=" + formatMoney(total) + "，每 tick 处理 " + perTick + " 个...");

                    String template = cmdTemplate == null ? "" : cmdTemplate.trim();
                    if (template.isBlank()) {
                        sendMessageSync(sender, ChatColor.RED + "配置错误：admin.reclaim.quicktax-command 为空，已取消。");
                        return;
                    }

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        new BukkitRunnable() {
                            int donePlayers = 0;
                            double doneTotal = 0.0D;

                            @Override
                            public void run() {
                                int batch = 0;
                                while (batch < perTick && !queue.isEmpty()) {
                                    var v = queue.poll();
                                    if (v == null) break;
                                    batch++;

                                    String name = safeName(v.playerName(), v.playerUuid());
                                    String amountText = formatMoney(v.outstandingMoney());
                                    String cmd = template
                                        .replace("{player}", name)
                                        .replace("{uuid}", v.playerUuid().toString())
                                        .replace("{amount}", amountText);

                                    boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                                    if (!ok) {
                                        sendMessageSync(sender, ChatColor.RED + "派发 QuickTax 命令失败（Bukkit.dispatchCommand=false），已中止。");
                                        sendMessageSync(sender, ChatColor.RED + "失败命令: " + cmd);
                                        cancel();
                                        return;
                                    }

                                    if (notifyPlayer && economyService != null && economyService.isReady()) {
                                        try {
                                            Player target = Bukkit.getPlayer(v.playerUuid());
                                            if (target != null && target.isOnline()) {
                                                String balanceText = formatMoney(economyService.getBalance(target));
                                                String msg = (notifyMessageTemplate == null ? "" : notifyMessageTemplate)
                                                    .replace("{player}", target.getName())
                                                    .replace("{uuid}", v.playerUuid().toString())
                                                    .replace("{amount}", amountText)
                                                    .replace("{balance}", balanceText);
                                                if (!msg.isBlank()) {
                                                    target.sendMessage(color(msg));
                                                }
                                            }
                                        } catch (Exception ignored) {
                                        }
                                    }

                                    donePlayers++;
                                    doneTotal += v.outstandingMoney();

                                    // 记账落库：用于避免重复回收
                                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                                        try {
                                            repository.recordSystemMoneyReclaim(v.playerUuid(), name, v.outstandingMoney(), System.currentTimeMillis());
                                        } catch (Exception e) {
                                            plugin.getLogger().warning("Failed to record system money reclaim for " + name + ": " + e.getMessage());
                                        }
                                    });
                                }

                                if (queue.isEmpty()) {
                                    sendMessageSync(sender, ChatColor.GREEN + "回收完成：玩家数=" + donePlayers + "，总额=" + formatMoney(doneTotal));
                                    cancel();
                                } else if (donePlayers > 0 && donePlayers % 100 == 0) {
                                    sendMessageSync(sender, ChatColor.GRAY + "回收进度：已处理玩家=" + donePlayers + "，已回收=" + formatMoney(doneTotal));
                                }
                            }
                        }.runTaskTimer(plugin, 1L, 1L);
                    });
                } catch (Exception e) {
                    sendMessageSync(sender, ChatColor.RED + "执行回收失败: " + e.getMessage());
                }
            });
            return true;
        }

        switch (action) {
            case "settarget" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.YELLOW + "该命令只能由玩家在游戏内执行（需手持物品）。");
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(ChatColor.YELLOW + "用法: /ecobrain admin settarget <数量>");
                    return true;
                }
                
                int newTarget;
                try {
                    newTarget = Integer.parseInt(args[2]);
                    if (newTarget <= 0) throw new NumberFormatException();
                } catch (NumberFormatException e) {
                    player.sendMessage(ChatColor.RED + "目标库存必须是大于 0 的整数。");
                    return true;
                }
                
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) {
                    player.sendMessage(ChatColor.RED + "请先手持需要修改目标库存的物品。");
                    return true;
                }
                
                ItemStack snapshot = hand.clone().asOne();
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        String base64 = itemSerializer.serializeToBase64(snapshot);
                        String hash = itemSerializer.sha256(base64);
                        
                        var recordOpt = repository.findByHash(hash);
                        if (recordOpt.isEmpty()) {
                            sendMessageSync(player, ChatColor.RED + "该物品尚未在市场中被收录（未发生过交易），请先由玩家卖出一次触发 IPO。");
                            return;
                        }
                        
                        var record = recordOpt.get();
                        repository.updateTargetInventoryWithProportionalCurrentScaling(
                            hash, 
                            record.getTargetInventory(), 
                            record.getCurrentInventory(), 
                            newTarget
                        );
                        
                        sendMessageSync(player, ChatColor.GREEN + "成功！已将主手物品的目标库存修改为: " + newTarget);
                        sendMessageSync(player, ChatColor.GREEN + "AI 会在下一个调控周期自动根据新的目标库存重新判定该物品的阶级(High/Mid/Low)。");
                    } catch (Exception e) {
                        sendMessageSync(player, ChatColor.RED + "执行失败: " + e.getMessage());
                    }
                });
                return true;
            }
            case "clear" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "用法: /ecobrain admin clear <hash>");
                    return true;
                }
                String hash = args[2];
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        repository.deleteByHash(hash);
                        sendMessageSync(sender, ChatColor.GREEN + "已清除物品市场档案: " + hash);
                    } catch (Exception e) {
                        sendMessageSync(sender, ChatColor.RED + "执行失败: " + e.getMessage());
                    }
                });
                return true;
            }
            case "freeze" -> {
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.YELLOW + "用法: /ecobrain admin freeze <hash>");
                    return true;
                }
                String hash = args[2];
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        repository.setFrozen(hash, true);
                        sendMessageSync(sender, ChatColor.RED + "已冻结: " + hash);
                    } catch (Exception e) {
                        sendMessageSync(sender, ChatColor.RED + "执行失败: " + e.getMessage());
                    }
                });
                return true;
            }
            case "unfreeze" -> {
                // /ecobrain admin unfreeze all
                if (args.length >= 3 && "all".equalsIgnoreCase(args[2])) {
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            int rows = repository.setAllFrozen(false);
                            sendMessageSync(sender, ChatColor.GREEN + "已解冻所有物品。" + (rows > 0 ? (" 更新行数=" + rows) : ""));
                        } catch (Exception e) {
                            sendMessageSync(sender, ChatColor.RED + "执行失败: " + e.getMessage());
                        }
                    });
                    return true;
                }

                // /ecobrain admin unfreeze <hash>
                if (args.length >= 3) {
                    String hash = args[2];
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        try {
                            repository.setFrozen(hash, false);
                            sendMessageSync(sender, ChatColor.GREEN + "已解冻: " + hash);
                        } catch (Exception e) {
                            sendMessageSync(sender, ChatColor.RED + "执行失败: " + e.getMessage());
                        }
                    });
                    return true;
                }

                // /ecobrain admin unfreeze  -> 解冻主手物品
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(ChatColor.YELLOW + "控制台用法: /ecobrain admin unfreeze <hash|all>");
                    return true;
                }
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) {
                    player.sendMessage(ChatColor.RED + "请先手持需要解冻的物品。");
                    return true;
                }
                ItemStack snapshot = hand.clone().asOne();
                plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        String base64 = itemSerializer.serializeToBase64(snapshot);
                        String hash = itemSerializer.sha256(base64);
                        repository.setFrozen(hash, false);
                        sendMessageSync(player, ChatColor.GREEN + "已解冻主手物品，对应 hash=" + hash);
                    } catch (Exception e) {
                        sendMessageSync(player, ChatColor.RED + "执行失败: " + e.getMessage());
                    }
                });
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.YELLOW + "未知动作: " + action);
                return true;
            }
        }
    }

    private void sendMessageSync(CommandSender sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> sender.sendMessage(message));
    }

    private String safeName(String recordedName, java.util.UUID uuid) {
        try {
            var offline = Bukkit.getOfflinePlayer(uuid);
            if (offline != null && offline.getName() != null && !offline.getName().isBlank()) {
                return offline.getName();
            }
        } catch (Exception ignored) {
        }
        if (recordedName != null && !recordedName.isBlank()) {
            return recordedName;
        }
        return uuid.toString();
    }

    private String formatMoney(double amount) {
        // QuickTax 支持小数；Vault 经济一般也支持两位小数，这里统一保留 2 位并强制英文小数点
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.US);
        DecimalFormat df = new DecimalFormat("0.00", symbols);
        return df.format(amount);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }
}
