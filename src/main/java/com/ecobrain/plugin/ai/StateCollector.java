package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.service.AMMCalculator;

import java.util.List;

/**
 * 异步状态采集器，提供 AI 所需的宏观特征向量。
 */
public class StateCollector {
    private final ItemMarketRepository repository;
    private final AMMCalculator ammCalculator;

    public StateCollector(ItemMarketRepository repository, AMMCalculator ammCalculator) {
        this.repository = repository;
        this.ammCalculator = ammCalculator;
    }

    /**
     * 采集当前市场状态：
     * [0] 录入物品数
     * [1] 总库存
     * [2] 平均库存偏离度
     * [3] 平均价格
     * [4] 过去2小时成交额
     * [5] 过去24小时成交额
     */
    public double[] collectState() {
        List<ItemMarketRecord> items = repository.findAll();
        double itemCount = items.size();
        double totalInventory = 0.0D;
        double imbalance = 0.0D;
        double avgPrice = 0.0D;
        for (ItemMarketRecord item : items) {
            totalInventory += item.getCurrentInventory();
            imbalance += Math.abs(item.getCurrentInventory() - item.getTargetInventory());
            avgPrice += ammCalculator.calculateCurrentPrice(item);
        }
        double now = System.currentTimeMillis();
        double vol2h = repository.queryVolumeSince((long) (now - 2 * 60 * 60 * 1000));
        double vol24h = repository.queryVolumeSince((long) (now - 24 * 60 * 60 * 1000));
        if (itemCount > 0) {
            imbalance = imbalance / itemCount;
            avgPrice = avgPrice / itemCount;
        }
        return new double[] {itemCount, totalInventory, imbalance, avgPrice, vol2h, vol24h};
    }
}
