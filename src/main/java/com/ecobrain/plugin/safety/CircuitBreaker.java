package com.ecobrain.plugin.safety;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.service.AMMCalculator;

import java.time.LocalDate;

/**
 * 金融熔断器：
 * - 单日涨跌超过 ±30% 冻结
 * - 库存极度枯竭时冻结
 */
public class CircuitBreaker {
    private final ItemMarketRepository repository;
    private final AMMCalculator ammCalculator;
    private volatile double dailyLimit;
    private volatile int criticalInventory;

    public CircuitBreaker(ItemMarketRepository repository, AMMCalculator ammCalculator,
                          PluginSettings.CircuitBreaker settings) {
        this.repository = repository;
        this.ammCalculator = ammCalculator;
        this.dailyLimit = settings.dailyLimitPercent();
        this.criticalInventory = settings.criticalInventory();
    }

    /**
     * 热更新熔断参数，立即作用于后续交易风控。
     */
    public void updateSettings(PluginSettings.CircuitBreaker settings) {
        this.dailyLimit = settings.dailyLimitPercent();
        this.criticalInventory = settings.criticalInventory();
    }

    /**
     * 卖出前风控：
     * - 卖出始终放行，用于补库存、恢复流动性
     * - 即使物品处于冻结状态，也允许玩家继续向系统卖出
     *   （这样可以避免市场因为历史冻结标记而永久卡死）
     */
    public boolean allowSell(ItemMarketRecord record) {
        return true;
    }

    /**
     * 买入前风控：
     * - 若已被冻结，禁止买入
     * - 若库存过低，直接拒绝买入（但不写永久冻结，避免无法通过卖出恢复）
     */
    public boolean allowBuy(ItemMarketRecord record) {
        if (repository.isFrozen(record.getItemHash())) {
            return false;
        }
        if (record.getCurrentInventory() <= criticalInventory) {
            return false;
        }
        return checkDailyPriceLimit(record);
    }

    /**
     * 价格熔断检查：单日涨跌超过阈值时写冻结标记。
     */
    private boolean checkDailyPriceLimit(ItemMarketRecord record) {
        double currentPrice = ammCalculator.calculateCurrentPrice(record);
        String dayKey = LocalDate.now().toString();
        double dayOpenPrice = repository.upsertAndGetDayOpenPrice(record.getItemHash(), currentPrice, dayKey);
        if (dayOpenPrice <= 0.0D) {
            return true;
        }
        double pct = (currentPrice - dayOpenPrice) / dayOpenPrice;
        if (Math.abs(pct) > dailyLimit) {
            repository.setFrozen(record.getItemHash(), true);
            return false;
        }
        return true;
    }
}
