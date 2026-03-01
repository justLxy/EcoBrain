package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 调度器：每 2 小时运行一次状态采样、奖励评估、经验回放训练与参数微调。
 */
public class AIScheduler {
    private enum AiAction {
        DOWN_PRICE,
        UP_PRICE,
        HOLD
    }

    private final JavaPlugin plugin;
    private final StateCollector stateCollector;
    private final DqnTrainer dqnTrainer;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;
    private volatile PluginSettings.AI settings;
    private BukkitTask task;

    public AIScheduler(JavaPlugin plugin, StateCollector stateCollector, DqnTrainer dqnTrainer,
                       ItemMarketRepository repository, PluginSettings.AI settings, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.stateCollector = stateCollector;
        this.dqnTrainer = dqnTrainer;
        this.repository = repository;
        this.settings = settings;
        this.itemSerializer = itemSerializer;
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
        AiAction aiAction = resolveAction(action);

        List<ItemMarketRecord> items = repository.findAll();
        MacroState macroBefore = collectMacroState(items, beforeState[4]);
        double inventoryImbalance = beforeState[2];
        double transactionVolume = beforeState[4];
        double inflationDelta = estimateInflation(beforeState);
        double reward = (settings.rewardW1() * transactionVolume)
            - (settings.rewardW2() * inflationDelta)
            - (settings.rewardW3() * inventoryImbalance);

        List<TuningResult> tuningResults = applyActionToMarket(items, aiAction);

        double[] afterState = stateCollector.collectState();
        dqnTrainer.observe(beforeState, action, reward, afterState);
        dqnTrainer.trainBatch(settings.trainBatchSize());

        if (settings.debugLog()) {
            logExplainableCycle(macroBefore, aiAction, reward, tuningResults);
        }
    }

    private List<TuningResult> applyActionToMarket(List<ItemMarketRecord> items, AiAction action) {
        List<TuningResult> results = new ArrayList<>();
        if (items.isEmpty()) {
            return results;
        }
        if (action == AiAction.HOLD) {
            return results;
        }
        double priceRate = action == AiAction.UP_PRICE ? settings.actionUpPriceRate() : settings.actionDownPriceRate();
        double kDelta = action == AiAction.UP_PRICE ? settings.kDelta() : -settings.kDelta();
        for (ItemMarketRecord record : items) {
            double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
            double oldBase = record.getBasePrice();
            double oldK = record.getKFactor();
            double newBasePrice = clamp(record.getBasePrice() * priceRate,
                record.getBasePrice() * (1.0D - limit),
                record.getBasePrice() * (1.0D + limit));
            double newK = clamp(record.getKFactor() + kDelta, settings.kMin(), settings.kMax());
            repository.updateTuning(record.getItemHash(), newBasePrice, newK);
            results.add(new TuningResult(
                readableItemName(record),
                record.getItemHash(),
                oldBase,
                newBasePrice,
                oldK,
                newK
            ));
        }
        return results;
    }

    private double estimateInflation(double[] state) {
        double averagePrice = state[3];
        double baseline = Math.max(1.0D, settings.inflationBaselinePrice());
        return Math.max(0.0D, (averagePrice - baseline) / baseline);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private AiAction resolveAction(int action) {
        return switch (action) {
            case 1 -> AiAction.UP_PRICE;
            case 0 -> AiAction.DOWN_PRICE;
            default -> AiAction.HOLD;
        };
    }

    private MacroState collectMacroState(List<ItemMarketRecord> records, double recentVolume2h) {
        if (records.isEmpty()) {
            return new MacroState(0, 0, 0.0D, recentVolume2h);
        }
        long totalPhysical = 0L;
        long totalTarget = 0L;
        for (ItemMarketRecord record : records) {
            totalPhysical += Math.max(0, record.getPhysicalStock());
            totalTarget += Math.max(1, record.getTargetInventory());
        }
        double ratio = totalTarget <= 0 ? 0.0D : (double) totalPhysical / totalTarget;
        return new MacroState(totalPhysical, totalTarget, ratio, recentVolume2h);
    }

    /**
     * 将 hash 物品转成人类可读名字：
     * 1) 若有自定义显示名则优先显示显示名
     * 2) 否则显示 Material 名称
     * 3) 失败时回退 Unknown Item
     */
    private String readableItemName(ItemMarketRecord record) {
        try {
            ItemStack itemStack = itemSerializer.deserializeFromBase64(record.getItemBase64());
            ItemMeta meta = itemStack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return ChatColor.stripColor(meta.getDisplayName());
            }
            if (itemStack.getType() != null) {
                return itemStack.getType().name();
            }
        } catch (Exception ignored) {
        }
        return "Unknown Item";
    }

    private void logExplainableCycle(MacroState state, AiAction action, double reward, List<TuningResult> results) {
        plugin.getLogger().info("[EcoBrain-AI] ===== 调控周期报告开始 =====");
        plugin.getLogger().info(String.format(
            "[EcoBrain-AI] State: physical_stock_total=%d, target_inventory_total=%d, stock_target_ratio=%.4f, volume_2h=%.2f",
            state.totalPhysicalStock(),
            state.totalTargetInventory(),
            state.stockTargetRatio(),
            state.recentVolume2h()
        ));
        plugin.getLogger().info(String.format("[EcoBrain-AI] Action: %s", action.name()));
        plugin.getLogger().info(String.format("[EcoBrain-AI] Reward: %.6f", reward));
        if (results.isEmpty()) {
            plugin.getLogger().info("[EcoBrain-AI] Result: 无活跃物品或动作为 HOLD，本轮无调参。");
        } else {
            for (TuningResult result : results) {
                String hashShort = result.hash().substring(0, Math.min(8, result.hash().length()));
                plugin.getLogger().info(String.format(
                    "[EcoBrain-AI] Result: [%s] (Hash: %s...) base_price [%.6f -> %.6f], k_factor [%.6f -> %.6f]",
                    result.itemName(),
                    hashShort,
                    result.oldBasePrice(),
                    result.newBasePrice(),
                    result.oldKFactor(),
                    result.newKFactor()
                ));
            }
        }
        plugin.getLogger().info("[EcoBrain-AI] ===== 调控周期报告结束 =====");
    }

    private record MacroState(long totalPhysicalStock, long totalTargetInventory, double stockTargetRatio,
                              double recentVolume2h) {}
    private record TuningResult(String itemName, String hash, double oldBasePrice, double newBasePrice,
                                double oldKFactor, double newKFactor) {}
}
