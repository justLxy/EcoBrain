package com.ecobrain.plugin.model;

/**
 * 当前市场快照，用于报价、GUI、风控与诊断。
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
    double liquidityDepth
) {
}
