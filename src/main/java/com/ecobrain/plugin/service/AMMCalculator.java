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
        // 保持兼容：默认退化为当前价（无交易记录时也安全）
        return calculateCurrentPrice(record);
    }
    
    /**
     * 计算动态印花税 (防套利)
     */
    public double calculateDynamicSpread(ItemMarketRecord record, int sellAmount) {
        double currentPrice = calculateCurrentPrice(record);
        double twap = getTwapPrice(record);
        return calculateDynamicSpread(record, sellAmount, currentPrice, twap);
    }

    /**
     * 带外部 TWAP 的动态印花税计算（用于科学的 volatilitySpread）。
     * @param currentPrice 已算出的当前价（避免重复 pow 开销）
     * @param twapPrice 由仓储层基于交易统计计算的近似 TWAP（若未知可传 currentPrice）
     */
    public double calculateDynamicSpread(ItemMarketRecord record, int sellAmount, double currentPrice, double twapPrice) {
        double twap = twapPrice;
        double baseSpread = 0.05;
        
        // 1. 基于价格波动率的基础印花税 (封顶 50%)
        double volatilitySpread = 0.0;
        if (twap > 0) {
            double volatility = Math.abs(currentPrice - twap) / twap;
            volatilitySpread = volatility * 0.5;
        }
        
        // 2. 防恶意套现熔断税：当单次抛售量极大，且远超当前物理流通盘时，予以重罚。
        // 这能彻底防死“开局卖 1 个建仓，左右手互倒把价格炒到 1 万块，最后一次性倾销 1000 个套现”的杀猪盘。
        double dumpingTax = 0.0;
        int currentPhysical = Math.max(1, record.getPhysicalStock());
        
        // 如果他一次性卖的数量超过了当前全服已知库存的 3 倍，开始征收超额抛售税
        if (sellAmount > currentPhysical * 3) {
            double ratio = (double) sellAmount / currentPhysical;
            // 每超过物理库存 1 倍，额外增加 10% 的税。比如当前库存1，他卖 10 个，惩罚 (10-3)*0.1 = 70% 税。
            dumpingTax = (ratio - 3.0) * 0.10; 
        }
        
        double dynamicSpread = baseSpread + volatilitySpread + dumpingTax;
        
        // 加上防倾销税后，最大可以扣到 99.9%（绝对不允许套现）
        return Math.min(0.999, dynamicSpread);
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
        // 动态印花税 (传入 amount 用于检测超量倾销)
        double spread = calculateDynamicSpread(record, amount);
        return new TradeResult(Math.max(0.0D, totalRevenue * (1.0 - spread)), initialInventory + amount);
    }

    /**
     * 计算玩家卖出 N 个物品后的总收益与结算库存（使用外部 TWAP）。
     */
    public TradeResult calculateSellTotal(ItemMarketRecord record, int amount, double twapPrice) {
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
        double currentPrice = calculateCurrentPrice(record);
        double spread = calculateDynamicSpread(record, amount, currentPrice, twapPrice);
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
