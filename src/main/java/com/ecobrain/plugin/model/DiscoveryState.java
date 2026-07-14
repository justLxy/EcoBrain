package com.ecobrain.plugin.model;

/**
 * 每个 item_hash 的卡尔曼滤波慢状态（对数公允价值的后验）。
 *
 * <ul>
 *   <li>{@code xLogValue} —— 对数公允价值后验均值。</li>
 *   <li>{@code pVar} —— 后验方差（不确定性）。不再 clamp 下限，让它随成交自然收敛、随空闲自然发散。</li>
 *   <li>{@code volEwma} —— 已实现对数收益方差的 EWMA（快速波动），直接进 A-S half-spread。</li>
 *   <li>{@code diversityEwma} —— 有效交易者多样性 EWMA。越低表示流量越集中在少数人，
 *       过程噪声被放大 → 报价越不敢跟随，用于缓解单人刷价的正反馈。</li>
 *   <li>{@code lastTradeAtMillis} —— 上一笔纳入滤波的成交时间，用于时间更新的 Δt。</li>
 * </ul>
 */
public record DiscoveryState(
    String itemHash,
    double xLogValue,
    double pVar,
    double volEwma,
    double diversityEwma,
    long lastTradeAtMillis,
    long updatedAtMillis
) {
    /**
     * 冷启动初值。锚点与初始不确定性由定价配置提供；此处给出安全默认，
     * 仓储层在 IPO/首次读取时会用配置值覆盖。
     */
    public static DiscoveryState initial(String itemHash) {
        return new DiscoveryState(
            itemHash,
            Math.log(100.0D),
            5.0D,
            0.0D,
            1.0D,
            0L,
            System.currentTimeMillis()
        );
    }

    public static DiscoveryState initial(String itemHash, double anchorPrice, double initialVariance) {
        return new DiscoveryState(
            itemHash,
            Math.log(Math.max(1.0e-6D, anchorPrice)),
            Math.max(1.0e-6D, initialVariance),
            0.0D,
            1.0D,
            0L,
            System.currentTimeMillis()
        );
    }
}
