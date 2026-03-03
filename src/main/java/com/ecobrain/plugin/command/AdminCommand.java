package com.ecobrain.plugin.command;

import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 管理命令执行器（/ecobrain admin ...）。
 */
public class AdminCommand {
    private final JavaPlugin plugin;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;

    public AdminCommand(JavaPlugin plugin, ItemMarketRepository repository, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.repository = repository;
        this.itemSerializer = itemSerializer;
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
            return true;
        }

        String action = args[1].toLowerCase();
        if ("exportdata".equals(action)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String fileName = repository.exportTransactionDataForTraining(plugin.getDataFolder());
                    sender.sendMessage(ChatColor.GREEN + "成功导出交易日志数据到: " + fileName);
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "导出数据失败: " + e.getMessage());
                }
            });
            return true;
        }

        if ("clearleaderboard".equalsIgnoreCase(action) || "resetleaderboard".equalsIgnoreCase(action)
            || "clear-ranking".equalsIgnoreCase(action) || "clearranking".equalsIgnoreCase(action)) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    int rows = repository.clearLeaderboard();
                    sender.sendMessage(ChatColor.GREEN + "已清空交易排行榜数据。" + (rows > 0 ? (" 删除行数=" + rows) : ""));
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
                            player.sendMessage(ChatColor.RED + "该物品尚未在市场中被收录（未发生过交易），请先由玩家卖出一次触发 IPO。");
                            return;
                        }
                        
                        var record = recordOpt.get();
                        repository.updateTargetInventoryWithProportionalCurrentScaling(
                            hash, 
                            record.getTargetInventory(), 
                            record.getCurrentInventory(), 
                            newTarget
                        );
                        
                        player.sendMessage(ChatColor.GREEN + "成功！已将主手物品的目标库存修改为: " + newTarget);
                        player.sendMessage(ChatColor.GREEN + "AI 会在下一个调控周期自动根据新的目标库存重新判定该物品的阶级(High/Mid/Low)。");
                    } catch (Exception e) {
                        player.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
                        sender.sendMessage(ChatColor.GREEN + "已清除物品市场档案: " + hash);
                    } catch (Exception e) {
                        sender.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
                        sender.sendMessage(ChatColor.RED + "已冻结: " + hash);
                    } catch (Exception e) {
                        sender.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
                            sender.sendMessage(ChatColor.GREEN + "已解冻所有物品。" + (rows > 0 ? (" 更新行数=" + rows) : ""));
                        } catch (Exception e) {
                            sender.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
                            sender.sendMessage(ChatColor.GREEN + "已解冻: " + hash);
                        } catch (Exception e) {
                            sender.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
                        player.sendMessage(ChatColor.GREEN + "已解冻主手物品，对应 hash=" + hash);
                    } catch (Exception e) {
                        player.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
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
}
