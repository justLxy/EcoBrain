package com.ecobrain.plugin.rewards;

import org.bukkit.Material;

import java.util.List;

public record RewardDefinition(
    String id,
    RewardType type,
    double target,
    Material displayMaterial,
    String displayName,
    List<String> displayLore,
    List<String> commands
) {}

