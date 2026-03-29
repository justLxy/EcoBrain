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
    private final Discovery discovery;
    private final Evidence evidence;
    private final AntiManipulation antiManipulation;
    private final MarketMaker marketMaker;
    private final Gui gui;

    public PluginSettings(Economy economy, Trade trade, CircuitBreaker circuitBreaker, AI ai,
                          Discovery discovery, Evidence evidence,
                          AntiManipulation antiManipulation, MarketMaker marketMaker,
                          Gui gui) {
        this.economy = economy;
        this.trade = trade;
        this.circuitBreaker = circuitBreaker;
        this.ai = ai;
        this.discovery = discovery;
        this.evidence = evidence;
        this.antiManipulation = antiManipulation;
        this.marketMaker = marketMaker;
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

    public Discovery discovery() {
        return discovery;
    }

    public Evidence evidence() {
        return evidence;
    }

    public AntiManipulation antiManipulation() {
        return antiManipulation;
    }

    public MarketMaker marketMaker() {
        return marketMaker;
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

        AI ai = new AI(
            c.getBoolean("ai.debug-log", true),
            c.getInt("ai.schedule-minutes", 120),
            c.getInt("ai.aov-window-hours", 24),
            c.getInt("ai.garbage-collection-days", 7),
            c.getDouble("ai.tuning.base-price-max-percent", 0.12D),
            c.getDouble("ai.tuning.inactivity-action-decay", 0.35D),
            c.getDouble("ai.tuning.k-delta", 0.03D),
            c.getDouble("ai.tuning.k-min", 0.2D),
            c.getDouble("ai.tuning.k-max", 3.0D),
            c.getDouble("ai.tuning.max-base-price", 5000000.0D),
            new AdaptiveTarget(
                c.getBoolean("ai.adaptive-target.enabled", true),
                c.getDouble("ai.adaptive-target.smoothing-factor", 0.20D),
                c.getInt("ai.adaptive-target.quantity-cap", 64)
            )
        );

        Discovery discovery = new Discovery(
            c.getDouble("discovery.anchor-price", 100.0D),
            c.getDouble("discovery.initial-sigma", 2.3D),
            c.getDouble("discovery.discovery-sigma-threshold", 0.35D),
            c.getInt("discovery.unknown-to-discovery.min-distinct-buyers-7d", 3),
            c.getDouble("discovery.unknown-to-discovery.min-trusted-buy-qty-7d", 16.0D),
            c.getInt("discovery.discovery-to-mature.min-distinct-buyers-7d", 10),
            c.getDouble("discovery.discovery-to-mature.min-trusted-buy-qty-7d", 64.0D)
        );

        Evidence evidence = new Evidence(
            c.getInt("evidence.buyers-window-days", 7),
            c.getInt("evidence.flow-window-hours", 24),
            c.getInt("evidence.reversal-window-hours", 2),
            c.getInt("evidence.player-history-window-days", 30),
            c.getInt("evidence.player-history-min-days", 3),
            c.getDouble("evidence.new-player-weight", 0.5D),
            c.getDouble("evidence.unknown-buy-weight", 1.0D),
            c.getDouble("evidence.unknown-sell-weight", 0.35D),
            c.getDouble("evidence.discovery-buy-weight", 1.0D),
            c.getDouble("evidence.discovery-sell-weight", 0.8D),
            c.getDouble("evidence.mature-buy-weight", 1.0D),
            c.getDouble("evidence.mature-sell-weight", 1.0D)
        );

        AntiManipulation antiManipulation = new AntiManipulation(
            c.getDouble("anti-manipulation.concentration-cap-share", 0.60D),
            c.getDouble("anti-manipulation.concentration-capped-weight", 0.25D),
            c.getDouble("anti-manipulation.promotion-block-score", 0.70D),
            c.getDouble("anti-manipulation.freeze-upside-score", 0.85D),
            c.getDouble("anti-manipulation.discovery-entry-max-score", 0.50D),
            c.getDouble("anti-manipulation.mature-max-top2-share", 0.45D)
        );

        MarketMaker marketMaker = new MarketMaker(
            c.getDouble("market-maker.inventory-pressure", 0.15D),
            c.getDouble("market-maker.sigma-spread-multiplier", 0.60D),
            c.getDouble("market-maker.manipulation-spread-multiplier", 0.40D),
            c.getDouble("market-maker.unknown-half-spread-floor", 1.40D),
            c.getDouble("market-maker.discovery-half-spread-floor", 0.50D),
            c.getDouble("market-maker.mature-half-spread-floor", 0.08D),
            c.getDouble("market-maker.slippage-per-depth", 0.20D),
            c.getDouble("market-maker.trusted-float-baseline", 16.0D),
            c.getDouble("market-maker.min-depth", 16.0D),
            c.getDouble("market-maker.max-depth", 2048.0D),
            c.getDouble("market-maker.sell-backstop-base", 0.05D),
            c.getDouble("market-maker.sell-backstop-trigger-ratio", 0.10D),
            c.getDouble("market-maker.sell-backstop-slope", 0.60D),
            c.getDouble("market-maker.sell-backstop-max-extra", 0.50D)
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

        return new PluginSettings(economy, trade, circuitBreaker, ai, discovery, evidence, antiManipulation, marketMaker, gui);
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
    public record AI(boolean debugLog, int scheduleMinutes,
                     int aovWindowHours,
                     int garbageCollectionDays,
                     double basePriceMaxPercent,
                     double inactivityActionDecay,
                     double kDelta, double kMin, double kMax,
                     double maxBasePrice,
                     AdaptiveTarget adaptiveTarget) {}

    public record AdaptiveTarget(boolean enabled, double smoothingFactor, int quantityCap) {}

    public record Discovery(double anchorPrice,
                            double initialSigma,
                            double discoverySigmaThreshold,
                            int unknownToDiscoveryMinDistinctBuyers7d,
                            double unknownToDiscoveryMinTrustedBuyQty7d,
                            int discoveryToMatureMinDistinctBuyers7d,
                            double discoveryToMatureMinTrustedBuyQty7d) {}

    public record Evidence(int buyersWindowDays,
                           int flowWindowHours,
                           int reversalWindowHours,
                           int playerHistoryWindowDays,
                           int playerHistoryMinDays,
                           double newPlayerWeight,
                           double unknownBuyWeight,
                           double unknownSellWeight,
                           double discoveryBuyWeight,
                           double discoverySellWeight,
                           double matureBuyWeight,
                           double matureSellWeight) {}

    public record AntiManipulation(double concentrationCapShare,
                                   double concentrationCappedWeight,
                                   double promotionBlockScore,
                                   double freezeUpsideScore,
                                   double discoveryEntryMaxScore,
                                   double matureMaxTop2Share) {}

    public record MarketMaker(double inventoryPressure,
                              double sigmaSpreadMultiplier,
                              double manipulationSpreadMultiplier,
                              double unknownHalfSpreadFloor,
                              double discoveryHalfSpreadFloor,
                              double matureHalfSpreadFloor,
                              double slippagePerDepth,
                              double trustedFloatBaseline,
                              double minDepth,
                              double maxDepth,
                              double sellBackstopBase,
                              double sellBackstopTriggerRatio,
                              double sellBackstopSlope,
                              double sellBackstopMaxExtra) {}

    public record Gui(String bulkSellTitle,
                      Material sellButtonMaterial, String sellButtonName, List<String> sellButtonLore,
                      Material cancelButtonMaterial, String cancelButtonName, List<String> cancelButtonLore,
                      Material fillerMaterial,
                      List<String> marketItemLoreTemplate) {}
}
