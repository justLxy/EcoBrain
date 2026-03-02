package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;

import java.util.Optional;

/**
 * 基于规则/统计的动态 target_inventory 计算器（按 item_hash 粒度）。
 */
public class TargetInventoryTuner {

    public record Decision(
        int oldTargetInventory,
        int suggestedTargetInventory,
        int appliedTargetInventory,
        String reason
    ) {}

    public Optional<Decision> tune(ItemMarketRecord item,
                                  PluginSettings.TargetInventory cfg,
                                  boolean hasRecentTrade,
                                  double recentVolume,
                                  double netFlowPerMinute,
                                  double dynamicAov,
                                  boolean isGlut,
                                  boolean isScarcity) {
        if (cfg == null || !cfg.enabled()) {
            return Optional.empty();
        }

        int oldTarget = Math.max(1, item.getTargetInventory());

        if (cfg.requireRecentTrade() && !hasRecentTrade) {
            return Optional.empty();
        }

        int minTarget = Math.max(1, cfg.min());
        int maxTarget = Math.max(minTarget, cfg.max());

        double basePrice = Math.max(1.0D, item.getBasePrice());
        double alpha = Math.max(0.0D, cfg.priceAlpha());
        double denom = Math.pow(basePrice, alpha);
        int baseTarget = clampInt((int) Math.round(cfg.priceScale() / Math.max(1e-9, denom)), minTarget, maxTarget);

        double normalizedVolume = recentVolume / Math.max(1.0D, dynamicAov);
        // 修正漏洞：为了防止大资金玩家通过制造虚假繁荣（左手倒右手刷极高的 recentVolume）
        // 来让 target_inventory 也就是资金池变深（从而在抛售时获得更小的滑点），
        // 当 basePrice 已经出现暴涨（处于泡沫期，比如被拉盘了），我们不再对 volume 给予正向奖励！
        // 只允许处于低价且健康的物品随着热度扩大池子。
        double volumeFactor = 1.0D;
        if (basePrice < 5000.0D) {
            volumeFactor += clamp(normalizedVolume, 0.0D, 3.0D) * Math.max(0.0D, cfg.volumeBoost());
        }

        // flow: BUY 记为正（买压强），SELL 记为负（卖压强）
        // 将数量级压到 [-1, 1]，避免不同服务器规模导致系数失真
        // 修正漏洞：当出现极端买压时（玩家囤货），不应该增加目标库存（这会加深资金池导致他们抛售时滑点变小）。
        // 相反，应当在买压过热时让目标库存变小（池子变浅），这样抛售滑点会极大，彻底粉碎“拉盘-出货”的操盘可能。
        // 所以，当 flowSignal > 0 (买入) 时，减少 target；当 flowSignal < 0 (卖出) 时，增加 target 承接抛压。
        double flowSignal = Math.tanh(netFlowPerMinute / 50.0D);
        double flowFactor = 1.0D - Math.max(0.0D, cfg.flowBoost()) * flowSignal; // 注意这里把 + 改成了 -

        double extremeFactor = 1.0D;
        String extremeReason = "NORMAL";
        if (isGlut) {
            extremeFactor *= clamp(cfg.glutMultiplier(), 0.0D, 10.0D);
            extremeReason = "GLUT";
        } else if (isScarcity) {
            extremeFactor *= clamp(cfg.scarcityMultiplier(), 0.0D, 10.0D);
            extremeReason = "SCARCITY";
        }

        int suggested = clampInt((int) Math.round(baseTarget * volumeFactor * flowFactor * extremeFactor), minTarget, maxTarget);
        int applied = smooth(oldTarget, suggested, cfg);

        if (applied == oldTarget) {
            return Optional.empty();
        }

        String reason = String.format("BASE=%d,V=%.4f,F=%.4f,%s", baseTarget, volumeFactor, flowFactor, extremeReason);
        return Optional.of(new Decision(oldTarget, suggested, applied, reason));
    }

    private int smooth(int oldTarget, int suggested, PluginSettings.TargetInventory cfg) {
        int delta = suggested - oldTarget;
        if (delta == 0) {
            return oldTarget;
        }

        int absDelta = Math.abs(delta);

        int percentLimit = Integer.MAX_VALUE;
        double p = Math.max(0.0D, cfg.perCycleMaxChangePercent());
        if (p > 0.0D) {
            percentLimit = Math.max(1, (int) Math.floor(oldTarget * p));
        }

        int absoluteLimit = Integer.MAX_VALUE;
        if (cfg.maxDelta() > 0) {
            absoluteLimit = cfg.maxDelta();
        }

        int limit = Math.min(percentLimit, absoluteLimit);
        if (limit == Integer.MAX_VALUE) {
            return suggested;
        }

        int appliedDelta = Math.min(absDelta, Math.max(0, limit));
        return oldTarget + (delta > 0 ? appliedDelta : -appliedDelta);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}

