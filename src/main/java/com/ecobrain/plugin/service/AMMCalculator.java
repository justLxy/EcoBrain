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
        // 卖出时系统扣除 5% 的手续费（即玩家只能获得 95% 的滑点总价）
        // 这就导致了天然的买卖差价（Spread），防止玩家利用原价无损来回倒腾（无风险套利）
        return new TradeResult(Math.max(0.0D, totalRevenue * 0.95D), initialInventory + amount);
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
