package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;

class TargetInventoryTunerTest {

    private static PluginSettings.TargetInventory cfg(boolean requireRecentTrade, double perCycleMaxChangePercent, int maxDelta) {
        return new PluginSettings.TargetInventory(
            true,
            1,
            1000,
            200.0D,   // priceScale
            0.0D,     // alpha (ignore price unless overridden per test)
            0.0D,     // volumeBoost
            0.0D,     // flowBoost
            2.0D,     // scarcityMultiplier
            0.5D,     // glutMultiplier
            perCycleMaxChangePercent,
            maxDelta,
            requireRecentTrade
        );
    }

    @Test
    void shouldSkipWhenRequireRecentTradeAndNoTrade() {
        TargetInventoryTuner tuner = new TargetInventoryTuner();
        ItemMarketRecord item = new ItemMarketRecord("h", "b", 100.0D, 1.0D, 100, 100, 10);

        Optional<TargetInventoryTuner.Decision> decision = tuner.tune(
            item,
            cfg(true, 1.0D, 1000),
            false,
            0.0D,
            0.0D,
            1000.0D,
            false,
            false
        );

        Assertions.assertTrue(decision.isEmpty());
    }

    @Test
    void shouldLimitChangeByPercent() {
        TargetInventoryTuner tuner = new TargetInventoryTuner();
        // oldTarget = 100, suggested baseTarget = 200 (priceScale=200, alpha=0)
        ItemMarketRecord item = new ItemMarketRecord("h", "b", 100.0D, 1.0D, 100, 100, 10);

        Optional<TargetInventoryTuner.Decision> decision = tuner.tune(
            item,
            cfg(false, 0.10D, 1000), // 10% max change
            true,
            10.0D,
            0.0D,
            1000.0D,
            false,
            false
        );

        Assertions.assertTrue(decision.isPresent());
        Assertions.assertEquals(200, decision.get().suggestedTargetInventory());
        Assertions.assertEquals(110, decision.get().appliedTargetInventory());
    }

    @Test
    void shouldLimitChangeByMaxDelta() {
        TargetInventoryTuner tuner = new TargetInventoryTuner();
        ItemMarketRecord item = new ItemMarketRecord("h", "b", 100.0D, 1.0D, 100, 100, 10);

        Optional<TargetInventoryTuner.Decision> decision = tuner.tune(
            item,
            cfg(false, 1.0D, 5), // maxDelta = 5
            true,
            10.0D,
            0.0D,
            1000.0D,
            false,
            false
        );

        Assertions.assertTrue(decision.isPresent());
        Assertions.assertEquals(200, decision.get().suggestedTargetInventory());
        Assertions.assertEquals(105, decision.get().appliedTargetInventory());
    }

    @Test
    void shouldApplyScarcityMultiplier() {
        TargetInventoryTuner tuner = new TargetInventoryTuner();
        ItemMarketRecord item = new ItemMarketRecord("h", "b", 100.0D, 1.0D, 10, 10, 1);

        Optional<TargetInventoryTuner.Decision> decision = tuner.tune(
            item,
            cfg(false, 1.0D, 1000),
            true,
            10.0D,
            0.0D,
            1000.0D,
            false,
            true
        );

        Assertions.assertTrue(decision.isPresent());
        // baseTarget=200; scarcityMultiplier=2.0 => 400
        Assertions.assertEquals(400, decision.get().suggestedTargetInventory());
        // perCycleMaxChangePercent=1.0 且 oldTarget=10 => 单周期最多 +10
        Assertions.assertEquals(20, decision.get().appliedTargetInventory());
    }

    @Test
    void shouldSuggestSmallerTargetForHigherPriceWhenAlphaPositive() {
        TargetInventoryTuner tuner = new TargetInventoryTuner();
        PluginSettings.TargetInventory priceCfg = new PluginSettings.TargetInventory(
            true,
            1,
            1000,
            10000.0D,
            0.5D,
            0.0D,
            0.0D,
            1.0D,
            1.0D,
            1.0D,
            1000,
            false
        );

        ItemMarketRecord cheap = new ItemMarketRecord("c", "b", 100.0D, 1.0D, 1, 1, 1);
        ItemMarketRecord expensive = new ItemMarketRecord("e", "b", 10000.0D, 1.0D, 1, 1, 1);

        int cheapSuggested = tuner.tune(cheap, priceCfg, true, 1.0D, 0.0D, 1000.0D, false, false)
            .orElseThrow()
            .suggestedTargetInventory();
        int expensiveSuggested = tuner.tune(expensive, priceCfg, true, 1.0D, 0.0D, 1000.0D, false, false)
            .orElseThrow()
            .suggestedTargetInventory();

        Assertions.assertTrue(expensiveSuggested < cheapSuggested);
    }
}

