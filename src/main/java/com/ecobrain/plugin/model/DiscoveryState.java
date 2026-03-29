package com.ecobrain.plugin.model;

/**
 * 每个 item_hash 的统计价值发现慢状态。
 */
public record DiscoveryState(
    String itemHash,
    double muLogPrice,
    double sigmaLogPrice,
    DiscoveryStage stage,
    double manipulationScore,
    double trustedBuyQty7d,
    double trustedSellQty7d,
    int distinctBuyers7d,
    int distinctSellers7d,
    double top2FlowShare24h,
    double reversalRate24h,
    double trustedFloat,
    double liquidityDepth,
    long updatedAtMillis
) {
    public static DiscoveryState initial(String itemHash) {
        return new DiscoveryState(
            itemHash,
            Math.log(100.0D),
            2.3D,
            DiscoveryStage.UNKNOWN,
            0.0D,
            0.0D,
            0.0D,
            0,
            0,
            0.0D,
            0.0D,
            1.0D,
            16.0D,
            System.currentTimeMillis()
        );
    }
}
