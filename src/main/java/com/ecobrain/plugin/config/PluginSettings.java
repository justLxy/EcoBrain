package com.ecobrain.plugin.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 插件配置快照。
 * 在启动阶段读取配置并转换成强类型对象，避免业务代码散落字符串路径。
 */
public class PluginSettings {
    private final Economy economy;
    private final Trade trade;
    private final CircuitBreaker circuitBreaker;
    private final AI ai;
    private final Gui gui;

    public PluginSettings(Economy economy, Trade trade, CircuitBreaker circuitBreaker, AI ai, Gui gui) {
        this.economy = economy;
        this.trade = trade;
        this.circuitBreaker = circuitBreaker;
        this.ai = ai;
        this.gui = gui;
    }

    public Economy economy() {
        return economy;
    }

    public Trade trade() {
        return trade;
    }

    public CircuitBreaker circuitBreaker() {
        return circuitBreaker;
    }

    public AI ai() {
        return ai;
    }

    public Gui gui() {
        return gui;
    }

    public static PluginSettings load(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();

        Economy economy = new Economy(
            c.getDouble("economy.ipo.base-price", 5000.0D),
            c.getInt("economy.ipo.target-inventory", 50),
            c.getDouble("economy.ipo.k-factor", 1.0D)
        );

        Trade trade = new Trade(
            c.getLong("trade.cooldown-ms", 1500L)
        );

        CircuitBreaker circuitBreaker = new CircuitBreaker(
            c.getDouble("circuit-breaker.daily-limit-percent", 0.30D),
            c.getInt("circuit-breaker.critical-inventory", 2)
        );

        AI ai = new AI(
            c.getInt("ai.schedule-hours", 2),
            c.getInt("ai.train-batch-size", 32),
            c.getInt("ai.replay-buffer-capacity", 4096),
            c.getDouble("ai.reward.w1-transaction-volume", 1.0D),
            c.getDouble("ai.reward.w2-inflation-delta", 0.6D),
            c.getDouble("ai.reward.w3-inventory-imbalance", 0.8D),
            c.getDouble("ai.tuning.action-up-price-rate", 1.03D),
            c.getDouble("ai.tuning.action-down-price-rate", 0.97D),
            c.getDouble("ai.tuning.per-cycle-max-change-percent", 0.05D),
            c.getDouble("ai.tuning.k-delta", 0.03D),
            c.getDouble("ai.tuning.k-min", 0.2D),
            c.getDouble("ai.tuning.k-max", 3.0D),
            c.getDouble("ai.tuning.inflation-baseline-price", 5000.0D)
        );

        Gui gui = new Gui(
            c.getString("gui.bulk-sell.title", "&2EcoBrain 批量出售舱"),
            parseMaterial(c.getString("gui.bulk-sell.sell-button.material"), Material.LIME_STAINED_GLASS_PANE),
            c.getString("gui.bulk-sell.sell-button.name", "&a确认出售"),
            c.getStringList("gui.bulk-sell.sell-button.lore"),
            parseMaterial(c.getString("gui.bulk-sell.cancel-button.material"), Material.RED_STAINED_GLASS_PANE),
            c.getString("gui.bulk-sell.cancel-button.name", "&c取消并退回"),
            c.getStringList("gui.bulk-sell.cancel-button.lore"),
            parseMaterial(c.getString("gui.bulk-sell.filler.material"), Material.BLACK_STAINED_GLASS_PANE),
            c.getStringList("gui.market.item-lore")
        );

        return new PluginSettings(economy, trade, circuitBreaker, ai, gui);
    }

    private static Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        return material == null ? fallback : material;
    }

    public record Economy(double ipoBasePrice, int ipoTargetInventory, double ipoKFactor) {}
    public record Trade(long cooldownMs) {}
    public record CircuitBreaker(double dailyLimitPercent, int criticalInventory) {}
    public record AI(int scheduleHours, int trainBatchSize, int replayBufferCapacity,
                     double rewardW1, double rewardW2, double rewardW3,
                     double actionUpPriceRate, double actionDownPriceRate,
                     double perCycleMaxChangePercent, double kDelta, double kMin, double kMax,
                     double inflationBaselinePrice) {}
    public record Gui(String bulkSellTitle,
                      Material sellButtonMaterial, String sellButtonName, List<String> sellButtonLore,
                      Material cancelButtonMaterial, String cancelButtonName, List<String> cancelButtonLore,
                      Material fillerMaterial,
                      List<String> marketItemLoreTemplate) {}
}
