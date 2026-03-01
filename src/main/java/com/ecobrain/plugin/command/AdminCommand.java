package com.ecobrain.plugin.command;

import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 管理命令执行器（/ecobrain admin ...）。
 */
public class AdminCommand {
    private final JavaPlugin plugin;
    private final ItemMarketRepository repository;

    public AdminCommand(JavaPlugin plugin, ItemMarketRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("ecobrain.admin")) {
            sender.sendMessage(ChatColor.RED + "你没有权限执行管理命令。");
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(ChatColor.YELLOW + "用法: /ecobrain admin <clear|freeze|unfreeze> <hash>");
            return true;
        }

        String action = args[1].toLowerCase();
        String hash = args[2];
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                switch (action) {
                    case "clear" -> {
                        repository.deleteByHash(hash);
                        sender.sendMessage(ChatColor.GREEN + "已清除物品市场档案: " + hash);
                    }
                    case "freeze" -> {
                        repository.setFrozen(hash, true);
                        sender.sendMessage(ChatColor.RED + "已冻结: " + hash);
                    }
                    case "unfreeze" -> {
                        repository.setFrozen(hash, false);
                        sender.sendMessage(ChatColor.GREEN + "已解冻: " + hash);
                    }
                    default -> sender.sendMessage(ChatColor.YELLOW + "未知动作: " + action);
                }
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "执行失败: " + e.getMessage());
            }
        });
        return true;
    }
}
