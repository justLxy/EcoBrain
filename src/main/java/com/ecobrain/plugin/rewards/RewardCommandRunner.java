package com.ecobrain.plugin.rewards;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;

public final class RewardCommandRunner {
    private final Plugin plugin;

    public RewardCommandRunner(Plugin plugin) {
        this.plugin = plugin;
    }

    public void run(Player player, List<String> commands) {
        if (commands == null || commands.isEmpty() || player == null) {
            return;
        }
        for (String raw : commands) {
            String line = substitute(raw, player);
            if (line.isBlank()) {
                continue;
            }
            if (line.regionMatches(true, 0, "player:", 0, "player:".length())) {
                String cmd = line.substring("player:".length()).trim();
                if (!cmd.isBlank()) {
                    player.performCommand(cmd);
                }
                continue;
            }
            if (line.regionMatches(true, 0, "console:", 0, "console:".length())) {
                String cmd = line.substring("console:".length()).trim();
                if (!cmd.isBlank()) {
                    CommandSender console = Bukkit.getConsoleSender();
                    Bukkit.dispatchCommand(console, cmd);
                }
                continue;
            }
            CommandSender console = Bukkit.getConsoleSender();
            Bukkit.dispatchCommand(console, line.trim());
        }
    }

    private String substitute(String text, Player player) {
        if (text == null) {
            return "";
        }
        return text
            .replace("{player}", player.getName())
            .replace("{uuid}", player.getUniqueId().toString())
            .replace("{world}", player.getWorld().getName());
    }
}

