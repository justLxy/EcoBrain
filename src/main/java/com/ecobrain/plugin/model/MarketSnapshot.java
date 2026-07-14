package com.ecobrain.plugin.model;

/**
 * 当前市场快照，用于报价、GUI、风控与诊断。
 *
 * <p>v5 起底层定价来自卡尔曼滤波 + Avellaneda-Stoikov，字段名保持兼容但语义重映射：
 * {@code sigmaLogPrice} = sqrt(后验方差 + 波动EWMA)，{@code manipulationScore} 变为“定价异常度”
 * 读数。{@code trustedBuyQty7d}/{@code trustedSellQty7d}/{@code distinctBuyers7d}/
 * {@code distinctSellers7d}/{@code top2FlowShare24h}/{@code reversalRate24h} 为兼容占位（0），
 * 供旧 GUI/占位符不报错，后续可清理。{@code currentPhysicalStockHint} 是构造快照时的真实库存，
 * 供 A-S 边际报价还原基准 reservation 使用。</p>
 */
public record MarketSnapshot(
    String itemHash,
    DiscoveryStage stage,
    double midPrice,
    double reservationPrice,
    double bidPrice,
    double askPrice,
    double halfSpread,
    double sigmaLogPrice,
    double manipulationScore,
    double trustedBuyQty7d,
    double trustedSellQty7d,
    int distinctBuyers7d,
    int distinctSellers7d,
    double top2FlowShare24h,
    double reversalRate24h,
    double trustedFloat,
    double liquidityDepth,
    int currentPhysicalStockHint
) {
}
