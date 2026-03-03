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
    public enum BuyCheckResult {
        ALLOW,
        FROZEN_BY_RISK,
        LOW_VIRTUAL_INVENTORY,
        POST_BUY_STOCK_PROTECTED
    }

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
    public BuyCheckResult checkBuy(ItemMarketRecord record, int amount) {
        if (isFrozenAfterDailyRefresh(record)) {
            return BuyCheckResult.FROZEN_BY_RISK;
        }
        if (record.getCurrentInventory() <= criticalInventory) {
            return BuyCheckResult.LOW_VIRTUAL_INVENTORY;
        }
        // 预检查：若本次买入会导致真实库存跌破熔断线，则拒绝交易。
        // （允许精准停靠在熔断线上；价格上行应由 AI 自己学习与执行）
        int postPhysicalStock = record.getPhysicalStock() - amount;
        if (postPhysicalStock < criticalInventory) {
            return BuyCheckResult.POST_BUY_STOCK_PROTECTED;
        }
        if (!checkDailyPriceLimit(record)) {
            return BuyCheckResult.FROZEN_BY_RISK;
        }
        return BuyCheckResult.ALLOW;
    }

    public boolean allowBuy(ItemMarketRecord record, int amount) {
        return checkBuy(record, amount) == BuyCheckResult.ALLOW;
    }

    /**
     * 先执行“跨天刷新”，再判断冻结状态。
     * 这样当日期切换后，旧的冻结标记会在 upsertAndGetDayOpenPrice 内被自动清除，
     * 避免出现“库存已经恢复但一直买不了”的永久冻结现象。
     */
    private boolean isFrozenAfterDailyRefresh(ItemMarketRecord record) {
        double currentPrice = ammCalculator.calculateCurrentPrice(record);
        String dayKey = LocalDate.now().toString();
        repository.upsertAndGetDayOpenPrice(record.getItemHash(), currentPrice, dayKey);
        return repository.isFrozen(record.getItemHash());
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
