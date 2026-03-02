package com.ecobrain.plugin.rewards;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RewardsManager {
    private final Plugin plugin;
    private final File file;
    private volatile RewardsConfig config;

    public RewardsManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rewards.yml");
        ensureDefaultFile();
        reload();
    }

    public RewardsConfig config() {
        return config;
    }

    public synchronized void reload() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        this.config = RewardsConfig.fromYaml(yml);
    }

    private void ensureDefaultFile() {
        if (file.exists()) {
            return;
        }
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建数据目录，rewards.yml 将不会生成。");
            return;
        }
        try {
            plugin.saveResource("rewards.yml", false);
        } catch (IllegalArgumentException e) {
            try {
                java.nio.file.Files.writeString(file.toPath(), "rewards: []\n", StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
    }
}

