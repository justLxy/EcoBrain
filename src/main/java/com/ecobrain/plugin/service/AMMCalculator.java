package com.ecobrain.plugin.service;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeResult;

/**
 * AMM 核心定价器。
 * 严格实现题设公式：
 * 1) 当前价格：P = BasePrice * (TargetInventory / CurrentInventory)^k
 * 2) 批量卖出：sum_{i=1..N} BasePrice * (TargetInventory / (CurrentInventory + i))^k
 * 3) 批量买入：sum_{i=1..N} BasePrice * (TargetInventory / max(1, CurrentInventory - i))^k
 */
public class AMMCalculator {

    public double calculateCurrentPrice(ItemMarketRecord record) {
        int currentInventory = Math.max(1, record.getCurrentInventory());
        return record.getBasePrice() * Math.pow((double) record.getTargetInventory() / currentInventory, record.getKFactor());
    }

    /**
     * 获取指定物品的 TWAP (时间加权平均价) 估算。
     * 实际中这通常由专门的定时任务维护，或者结合最近的交易记录，这里为了实现简单我们在运行时利用当前价格平滑处理。
     */
    public double getTwapPrice(ItemMarketRecord record) {
        // 在完整的数据库实现中，会查询记录表内的价格均值，这里做简单近似处理
        return calculateCurrentPrice(record);
    }
    
    /**
     * 计算动态印花税 (防套利)
     */
    public double calculateDynamicSpread(ItemMarketRecord record) {
        double currentPrice = calculateCurrentPrice(record);
        double twap = getTwapPrice(record);
        double baseSpread = 0.05;
        
        if (twap > 0) {
            double volatility = Math.abs(currentPrice - twap) / twap;
            double dynamicSpread = baseSpread + (volatility * 0.5);
            return Math.min(0.50, dynamicSpread); // 封顶 50%
        }
        
        return baseSpread;
    }

    /**
     * 计算玩家卖出 N 个物品后的总收益与结算库存。
     */
    public TradeResult calculateSellTotal(ItemMarketRecord record, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        double totalRevenue = 0.0D;
        int initialInventory = Math.max(1, record.getCurrentInventory());
        for (int i = 1; i <= amount; i++) {
            int stepInventory = initialInventory + i;
            totalRevenue += record.getBasePrice()
                * Math.pow((double) record.getTargetInventory() / Math.max(1, stepInventory), record.getKFactor());
        }
        // 动态印花税
        double spread = calculateDynamicSpread(record);
        return new TradeResult(Math.max(0.0D, totalRevenue * (1.0 - spread)), initialInventory + amount);
    }

    /**
     * 计算玩家买入 N 个物品后的总支出与结算库存。
     * 该函数会逐步降低库存并提高边际价格，保证买入滑点真实可感知。
     */
    public TradeResult calculateBuyTotal(ItemMarketRecord record, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        int initialInventory = Math.max(1, record.getCurrentInventory());
        if (amount >= initialInventory) {
            throw new IllegalArgumentException("amount exceeds available inventory");
        }

        double totalCost = 0.0D;
        for (int i = 1; i <= amount; i++) {
            int stepInventory = Math.max(1, initialInventory - i);
            totalCost += record.getBasePrice()
                * Math.pow((double) record.getTargetInventory() / stepInventory, record.getKFactor());
        }
        return new TradeResult(Math.max(0.0D, totalCost), Math.max(1, initialInventory - amount));
    }
}
