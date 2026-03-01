package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * AI 调度器：每 2 小时运行一次状态采样、奖励评估、经验回放训练与参数微调。
 */
public class AIScheduler {
    private final JavaPlugin plugin;
    private final StateCollector stateCollector;
    private final DqnTrainer dqnTrainer;
    private final ItemMarketRepository repository;
    private volatile PluginSettings.AI settings;
    private BukkitTask task;

    public AIScheduler(JavaPlugin plugin, StateCollector stateCollector, DqnTrainer dqnTrainer,
                       ItemMarketRepository repository, PluginSettings.AI settings) {
        this.plugin = plugin;
        this.stateCollector = stateCollector;
        this.dqnTrainer = dqnTrainer;
        this.repository = repository;
        this.settings = settings;
    }

    public void start() {
        long initialDelay = 20L * 30L;
        long period = 20L * 60L * 60L * Math.max(1, settings.scheduleHours());
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::tick, initialDelay, period);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * 热更新 AI 参数并重启调度周期。
     */
    public void updateSettingsAndRestart(PluginSettings.AI settings) {
        this.settings = settings;
        stop();
        start();
    }

    /**
     * AI 主循环：
     * 1) 收集当前状态
     * 2) 选择调控动作
     * 3) 计算奖励并记录经验
     * 4) 训练网络并更新所有活跃物品参数
     */
    private void tick() {
        double[] beforeState = stateCollector.collectState();
        int action = dqnTrainer.chooseAction(beforeState);

        List<ItemMarketRecord> items = repository.findAll();
        double inventoryImbalance = beforeState[2];
        double transactionVolume = beforeState[4];
        double inflationDelta = estimateInflation(beforeState);
        double reward = (settings.rewardW1() * transactionVolume)
            - (settings.rewardW2() * inflationDelta)
            - (settings.rewardW3() * inventoryImbalance);

        applyActionToMarket(items, action);

        double[] afterState = stateCollector.collectState();
        dqnTrainer.observe(beforeState, action, reward, afterState);
        dqnTrainer.trainBatch(settings.trainBatchSize());
    }

    private void applyActionToMarket(List<ItemMarketRecord> items, int action) {
        if (items.isEmpty()) {
            return;
        }
        double priceRate = action == 1 ? settings.actionUpPriceRate() : settings.actionDownPriceRate();
        double kDelta = action == 1 ? settings.kDelta() : -settings.kDelta();
        for (ItemMarketRecord record : items) {
            double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
            double newBasePrice = clamp(record.getBasePrice() * priceRate,
                record.getBasePrice() * (1.0D - limit),
                record.getBasePrice() * (1.0D + limit));
            double newK = clamp(record.getKFactor() + kDelta, settings.kMin(), settings.kMax());
            repository.updateTuning(record.getItemHash(), newBasePrice, newK);
        }
    }

    private double estimateInflation(double[] state) {
        double averagePrice = state[3];
        double baseline = Math.max(1.0D, settings.inflationBaselinePrice());
        return Math.max(0.0D, (averagePrice - baseline) / baseline);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
