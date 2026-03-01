package com.ecobrain.plugin.service;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AMMCalculatorTest {

    @Test
    void shouldCalculateCurrentPriceByExactFormula() {
        AMMCalculator calculator = new AMMCalculator();
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 5000.0D, 1.0D, 50, 25, 25);

        double price = calculator.calculateCurrentPrice(record);

        Assertions.assertEquals(10000.0D, price, 1.0e-6);
    }

    @Test
    void shouldCalculateSellTotalByDiscreteSummation() {
        AMMCalculator calculator = new AMMCalculator();
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 5000.0D, 1.0D, 50, 50, 50);

        TradeResult result = calculator.calculateSellTotal(record, 2);

        double expected = 5000.0D * (50.0D / 51.0D) + 5000.0D * (50.0D / 52.0D);
        Assertions.assertEquals(expected, result.getTotalPrice(), 1.0e-6);
        Assertions.assertEquals(52, result.getPostInventory());
    }

    @Test
    void shouldCalculateBuyTotalByDiscreteSummation() {
        AMMCalculator calculator = new AMMCalculator();
        ItemMarketRecord record = new ItemMarketRecord("hash", "base64", 5000.0D, 1.0D, 50, 60, 60);

        TradeResult result = calculator.calculateBuyTotal(record, 2);

        double expected = 5000.0D * (50.0D / 59.0D) + 5000.0D * (50.0D / 58.0D);
        Assertions.assertEquals(expected, result.getTotalPrice(), 1.0e-6);
        Assertions.assertEquals(58, result.getPostInventory());
    }
}
