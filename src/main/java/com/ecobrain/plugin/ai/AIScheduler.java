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
 * AI 调度器：
 * 以“单个物品”为粒度执行状态提取、动作决策、奖励回传和参数微调，
 * 避免全市场“一刀切”导致健康物品被连坐调价。
 */
public class AIScheduler {
    private enum AiAction {
        DOWN_PRICE,
        UP_PRICE,
        HOLD
    }

    private final JavaPlugin plugin;
    private final DqnTrainer dqnTrainer;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;
    private volatile PluginSettings.AI settings;
    private BukkitTask task;

    public AIScheduler(JavaPlugin plugin, DqnTrainer dqnTrainer,
                       ItemMarketRepository repository, PluginSettings.AI settings, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.dqnTrainer = dqnTrainer;
        this.repository = repository;
        this.settings = settings;
        this.itemSerializer = itemSerializer;
    }

    public void start() {
        long initialDelay = 20L * 30L;
        long period = 20L * 60L * Math.max(1, settings.scheduleMinutes());
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
        List<ItemMarketRecord> items = repository.findAll();
        if (items.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long windowMs = Math.max(1, settings.scheduleMinutes()) * 60L * 1000L;
        long since = now - windowMs;
        double windowMinutes = Math.max(1, settings.scheduleMinutes());

        double globalInflationRate = estimateGlobalInflation(items);
        List<ItemCycleLog> cycleLogs = new ArrayList<>();

        for (ItemMarketRecord item : items) {
            double saturation = calculateSaturation(item);
            double recentFlow = repository.queryItemNetFlowSince(item.getItemHash(), since) / windowMinutes;
            double[] state = new double[] {saturation, recentFlow, globalInflationRate};

            int actionIndex = dqnTrainer.chooseAction(state);
            AiAction action = resolveAction(actionIndex);

            double recentVolume = repository.queryItemVolumeSince(item.getItemHash(), since);
            double imbalance = Math.abs(item.getCurrentInventory() - item.getTargetInventory())
                / (double) Math.max(1, item.getTargetInventory());
            double reward = (settings.rewardW1() * recentVolume)
                - (settings.rewardW2() * Math.abs(globalInflationRate))
                - (settings.rewardW3() * imbalance);

            TuningResult result = applyActionToItem(item, action);
            ItemMarketRecord latest = repository.findByHash(item.getItemHash()).orElse(item);
            double nextSaturation = calculateSaturation(latest);
            double[] nextState = new double[] {nextSaturation, recentFlow, globalInflationRate};

            dqnTrainer.observe(state, actionIndex, reward, nextState);

            if (settings.debugLog()) {
                cycleLogs.add(new ItemCycleLog(
                    readableItemName(item),
                    item.getItemHash(),
                    saturation,
                    recentFlow,
                    globalInflationRate,
                    action,
                    reward,
                    result
                ));
            }
        }

        dqnTrainer.trainBatch(settings.trainBatchSize());

        if (settings.debugLog()) {
            logExplainableCycle(cycleLogs);
        }
    }

    private TuningResult applyActionToItem(ItemMarketRecord item, AiAction action) {
        if (action == AiAction.HOLD) {
            return null;
        }
        double priceRate = action == AiAction.UP_PRICE ? settings.actionUpPriceRate() : settings.actionDownPriceRate();
        double kDelta = action == AiAction.UP_PRICE ? settings.kDelta() : -settings.kDelta();
        double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
        double oldBase = item.getBasePrice();
        double oldK = item.getKFactor();
        double newBasePrice = clamp(item.getBasePrice() * priceRate,
            item.getBasePrice() * (1.0D - limit),
            item.getBasePrice() * (1.0D + limit));
        double newK = clamp(item.getKFactor() + kDelta, settings.kMin(), settings.kMax());
        repository.updateTuning(item.getItemHash(), newBasePrice, newK);
        return new TuningResult(
            readableItemName(item),
            item.getItemHash(),
            oldBase,
            newBasePrice,
            oldK,
            newK
        );
    }

    private double estimateGlobalInflation(List<ItemMarketRecord> items) {
        if (items.isEmpty()) {
            return 0.0D;
        }
        double totalCurrentPrice = 0.0D;
        for (ItemMarketRecord item : items) {
            int currentInventory = Math.max(1, item.getCurrentInventory());
            totalCurrentPrice += item.getBasePrice()
                * Math.pow((double) item.getTargetInventory() / currentInventory, item.getKFactor());
        }
        double averagePrice = totalCurrentPrice / items.size();
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

    private double calculateSaturation(ItemMarketRecord item) {
        return item.getCurrentInventory() / (double) Math.max(1, item.getTargetInventory());
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

    private void logExplainableCycle(List<ItemCycleLog> logs) {
        plugin.getLogger().info("[EcoBrain-AI] ===== 调控周期报告开始 =====");
        if (logs.isEmpty()) {
            plugin.getLogger().info("[EcoBrain-AI] 本轮无活跃商品。");
            plugin.getLogger().info("[EcoBrain-AI] ===== 调控周期报告结束 =====");
            return;
        }
        for (ItemCycleLog log : logs) {
            String hashShort = log.hash().substring(0, Math.min(8, log.hash().length()));
            plugin.getLogger().info(String.format(
                "[EcoBrain-AI] Item: [%s] (Hash: %s...)",
                log.itemName(), hashShort
            ));
            plugin.getLogger().info(String.format(
                "[EcoBrain-AI] State: saturation=%.4f, recent_flow=%.4f, global_inflation=%.6f",
                log.saturation(), log.recentFlow(), log.globalInflationRate()
            ));
            plugin.getLogger().info(String.format("[EcoBrain-AI] Action: %s", log.action().name()));
            plugin.getLogger().info(String.format("[EcoBrain-AI] Reward: %.6f", log.reward()));
            if (log.result() == null) {
                plugin.getLogger().info("[EcoBrain-AI] Result: HOLD，本轮参数不变。");
            } else {
                plugin.getLogger().info(String.format(
                    "[EcoBrain-AI] Result: base_price [%.6f -> %.6f], k_factor [%.6f -> %.6f]",
                    log.result().oldBasePrice(),
                    log.result().newBasePrice(),
                    log.result().oldKFactor(),
                    log.result().newKFactor()
                ));
            }
        }
        plugin.getLogger().info("[EcoBrain-AI] ===== 调控周期报告结束 =====");
    }

    private record ItemCycleLog(String itemName, String hash, double saturation, double recentFlow,
                                double globalInflationRate, AiAction action, double reward,
                                TuningResult result) {}
    private record TuningResult(String itemName, String hash, double oldBasePrice, double newBasePrice,
                                double oldKFactor, double newKFactor) {}
}
