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
    private final Pricing pricing;
    private final Gui gui;

    public PluginSettings(Economy economy, Trade trade, CircuitBreaker circuitBreaker,
                          Pricing pricing, Gui gui) {
        this.economy = economy;
        this.trade = trade;
        this.circuitBreaker = circuitBreaker;
        this.pricing = pricing;
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

    public Pricing pricing() {
        return pricing;
    }

    public Gui gui() {
        return gui;
    }

    public static PluginSettings load(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();

        Economy economy = new Economy(
            c.getDouble("economy.ipo.base-price", 100.0D),
            c.getDouble("economy.ipo.k-factor", 1.0D),
            c.getBoolean("economy.ipo.zero-trust", true),
            c.getDouble("economy.treasury.initial-balance", 0.0D)
        );

        Trade trade = new Trade(
            c.getLong("trade.cooldown-ms", 1500L)
        );

        CircuitBreaker circuitBreaker = new CircuitBreaker(
            c.getDouble("circuit-breaker.daily-limit-percent", 0.30D),
            c.getInt("circuit-breaker.critical-inventory", 2)
        );

        Pricing pricing = new Pricing(
            c.getDouble("pricing.gamma", 0.15D),
            c.getDouble("pricing.process-noise-per-hour", 0.02D),
            c.getDouble("pricing.observation-noise-base", 1.0D),
            c.getDouble("pricing.student-t-dof", 4.0D),
            c.getDouble("pricing.volatility-half-life-hours", 12.0D),
            c.getDouble("pricing.base-fee", 0.05D),
            c.getDouble("pricing.inventory-risk-weight", 1.0D),
            c.getDouble("pricing.treasury-risk-weight", 1.0D),
            c.getDouble("pricing.trusted-float-baseline", 16.0D),
            c.getDouble("pricing.min-depth", 16.0D),
            c.getDouble("pricing.max-depth", 2048.0D),
            c.getDouble("pricing.anchor-price", 100.0D),
            c.getDouble("pricing.initial-variance", 5.0D),
            c.getInt("pricing.diversity-window-hours", 24),
            c.getInt("pricing.garbage-collection-days", 7)
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

        return new PluginSettings(economy, trade, circuitBreaker, pricing, gui);
    }

    private static Material parseMaterial(String raw, Material fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(raw);
        return material == null ? fallback : material;
    }

    public record Economy(double ipoBasePrice, double ipoKFactor, boolean zeroTrustIpo, double treasuryInitialBalance) {}
    public record Trade(long cooldownMs) {}
    public record CircuitBreaker(double dailyLimitPercent, int criticalInventory) {}

    /**
     * v5 定价引擎参数（卡尔曼滤波 + Avellaneda-Stoikov 做市）。
     *
     * <ul>
     *   <li>{@code gamma} —— A-S 风险厌恶系数，控制库存/金库偏移与价差强度。</li>
     *   <li>{@code processNoisePerHour} —— 卡尔曼过程噪声 q，公允价值单位时间随机游走强度。</li>
     *   <li>{@code observationNoiseBase} —— 单笔成交观测噪声基准 r0（按成交量缩小）。</li>
     *   <li>{@code studentTDof} —— 鲁棒似然自由度，越小越抗异常成交（洗价自动降权）。</li>
     *   <li>{@code volatilityHalfLifeHours} —— 已实现波动 EWMA 半衰期。</li>
     *   <li>{@code baseFee} —— 最小 half-spread（做市手续费下限）。</li>
     *   <li>{@code inventoryRiskWeight}/{@code treasuryRiskWeight} —— 库存/金库风险进 reservation price 的权重。</li>
     *   <li>{@code trustedFloatBaseline}/{@code minDepth}/{@code maxDepth} —— 流动性深度参数。</li>
     *   <li>{@code anchorPrice}/{@code initialVariance} —— 新物品冷启动中性锚与初始不确定性。</li>
     *   <li>{@code diversityWindowHours} —— 交易者多样性统计窗口。</li>
     *   <li>{@code garbageCollectionDays} —— 滞销物品自动清理天数。</li>
     * </ul>
     */
    public record Pricing(double gamma,
                          double processNoisePerHour,
                          double observationNoiseBase,
                          double studentTDof,
                          double volatilityHalfLifeHours,
                          double baseFee,
                          double inventoryRiskWeight,
                          double treasuryRiskWeight,
                          double trustedFloatBaseline,
                          double minDepth,
                          double maxDepth,
                          double anchorPrice,
                          double initialVariance,
                          int diversityWindowHours,
                          int garbageCollectionDays) {}

    public record Gui(String bulkSellTitle,
                      Material sellButtonMaterial, String sellButtonName, List<String> sellButtonLore,
                      Material cancelButtonMaterial, String cancelButtonName, List<String> cancelButtonLore,
                      Material fillerMaterial,
                      List<String> marketItemLoreTemplate) {}
}
