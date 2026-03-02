package com.ecobrain.plugin.rewards;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        List<Map<?, ?>> mapList = c.getMapList("rewards");
        for (Map<?, ?> map : mapList) {
            String id = asString(map.get("id"));
            String typeStr = asString(map.get("type"));
            if (id == null || id.isBlank() || typeStr == null || typeStr.isBlank()) {
                continue;
            }
            RewardType type;
            try {
                type = RewardType.valueOf(typeStr.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            double target = asDouble(map.get("target"), 0.0D);
            if (target <= 0.0D) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> display = (map.get("display") instanceof Map<?, ?> dm)
                ? (Map<String, Object>) dm
                : null;
            Material mat = parseMaterial(display == null ? null : asString(display.get("material")), Material.PAPER);
            String name = display == null ? "&f奖励" : Objects.toString(asString(display.get("name")), "&f奖励");
            List<String> lore = toStringList(display == null ? null : display.get("lore"));
            List<String> commands = toStringList(map.get("commands"));

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

    private static String asString(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString();
        return s.isBlank() ? null : s;
    }

    private static double asDouble(Object o, double fallback) {
        if (o == null) {
            return fallback;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(o.toString().trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static List<String> toStringList(Object o) {
        if (o == null) {
            return List.of();
        }
        if (o instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object v : list) {
                if (v != null) {
                    out.add(v.toString());
                }
            }
            return out;
        }
        return List.of(o.toString());
    }
}

