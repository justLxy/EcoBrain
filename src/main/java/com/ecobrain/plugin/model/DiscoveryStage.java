package com.ecobrain.plugin.model;

/**
 * 统计价值发现阶段。
 */
public enum DiscoveryStage {
    UNKNOWN,
    DISCOVERY,
    MATURE;

    public double stageMultiplier() {
        return switch (this) {
            case UNKNOWN -> 1.0D;
            case DISCOVERY -> 2.0D;
            case MATURE -> 4.0D;
        };
    }
}
