package com.ecobrain.plugin.rewards;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RewardsConfig {
    public record Gui(String title, int size, Material borderMaterial, String borderName, int backSlot, Material backMaterial, String backName) {}

    private final Gui gui;
    private final List<RewardDefinition> rewards;

    public RewardsConfig(Gui gui, List<RewardDefinition> rewards) {
        this.gui = gui;
        this.rewards = rewards;
    }

    public Gui gui() {
        return gui;
    }

    public List<RewardDefinition> rewards() {
        return rewards;
    }

    public static RewardsConfig fromYaml(YamlConfiguration c) {
        String title = Objects.toString(c.getString("gui.title", "&bEcoBrain - 奖励"), "&bEcoBrain - 奖励");
        int size = clampInventorySize(c.getInt("gui.size", 54));
        Material borderMaterial = parseMaterial(c.getString("gui.border.material"), Material.BLACK_STAINED_GLASS_PANE);
        String borderName = Objects.toString(c.getString("gui.border.name", "&8 "), "&8 ");
        int backSlot = clampSlot(c.getInt("gui.back.slot", 49), size);
        Material backMaterial = parseMaterial(c.getString("gui.back.material"), Material.ARROW);
        String backName = Objects.toString(c.getString("gui.back.name", "&e返回"), "&e返回");

        List<RewardDefinition> rewards = new ArrayList<>();
        List<?> list = c.getList("rewards", Collections.emptyList());
        for (Object o : list) {
            if (!(o instanceof java.util.Map<?, ?> map)) {
                continue;
            }
            YamlConfiguration tmp = new YamlConfiguration();
            for (var e : map.entrySet()) {
                if (e.getKey() != null) {
                    tmp.set(e.getKey().toString(), e.getValue());
                }
            }
            String id = tmp.getString("id");
            String typeStr = tmp.getString("type");
            if (id == null || id.isBlank() || typeStr == null || typeStr.isBlank()) {
                continue;
            }
            RewardType type;
            try {
                type = RewardType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            double target = tmp.getDouble("target", 0.0D);
            if (target <= 0.0D) {
                continue;
            }
            ConfigurationSection display = tmp.getConfigurationSection("display");
            Material mat = parseMaterial(display == null ? null : display.getString("material"), Material.PAPER);
            String name = display == null ? "&f奖励" : Objects.toString(display.getString("name", "&f奖励"), "&f奖励");
            List<String> lore = display == null ? List.of() : display.getStringList("lore");
            List<String> commands = tmp.getStringList("commands");
            rewards.add(new RewardDefinition(id, type, target, mat, name, lore, commands));
        }

        return new RewardsConfig(new Gui(title, size, borderMaterial, borderName, backSlot, backMaterial, backName), List.copyOf(rewards));
    }

    private static int clampInventorySize(int size) {
        int s = Math.max(9, Math.min(54, size));
        return (s / 9) * 9;
    }

    private static int clampSlot(int slot, int size) {
        return Math.max(0, Math.min(size - 1, slot));
    }

    private static Material parseMaterial(String input, Material fallback) {
        if (input == null || input.isBlank()) {
            return fallback;
        }
        Material m = Material.matchMaterial(input.trim());
        return m == null ? fallback : m;
    }
}

