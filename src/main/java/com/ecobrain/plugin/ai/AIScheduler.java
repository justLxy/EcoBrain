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

    private enum SurgeType {
        NONE,
        SCARCITY_SURGE,
        GLUT_CRASH
    }

    private final JavaPlugin plugin;
    private final DqnTrainer dqnTrainer;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;
    private volatile PluginSettings.AI settings;
    private BukkitTask task;

    private volatile PluginSettings fullSettings;

    public AIScheduler(JavaPlugin plugin, DqnTrainer dqnTrainer,
                       ItemMarketRepository repository, PluginSettings.AI settings, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.dqnTrainer = dqnTrainer;
        this.repository = repository;
        this.settings = settings;
        this.itemSerializer = itemSerializer;
    }

    public void setFullSettings(PluginSettings fullSettings) {
        this.fullSettings = fullSettings;
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
    public void updateSettingsAndRestart(PluginSettings.AI settings, PluginSettings fullSettings) {
        this.settings = settings;
        this.fullSettings = fullSettings;
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

        long oneDaySince = now - 24L * 60L * 60L * 1000L;
        double dynamicAov = repository.queryDynamicAovSince(oneDaySince);
        if (dynamicAov <= 0.0D) {
            if (fullSettings != null) {
                dynamicAov = fullSettings.economy().ipoBasePrice() * 10.0D;
            } else {
                dynamicAov = 50000.0D;
            }
        }

        double cycleNetEmission = repository.queryNetEmissionSince(since);
        double globalInflationRate = cycleNetEmission / dynamicAov;

        if (settings.debugLog()) {
            plugin.getLogger().info("[EcoBrain-AI] ===== 微观调控周期报告开始 =====");
            plugin.getLogger().info(String.format("[EcoBrain-AI] 全局宏观状态: 24h动态客单价(基准) = %.2f 金币", dynamicAov));
            plugin.getLogger().info(String.format("[EcoBrain-AI] 全局特征提取 -> 周期净印发=%.2f, global_inflation=%.6f", cycleNetEmission, globalInflationRate));
        }

        for (ItemMarketRecord item : items) {
            // 1. State 的颗粒度降维：单品特征
            double saturation = calculateSaturation(item);
            double recentFlow = repository.queryItemNetFlowSince(item.getItemHash(), since) / windowMinutes;
            double[] state = new double[] {saturation, recentFlow, globalInflationRate};

            // 2. Action 的独立计算
            int actionIndex = dqnTrainer.chooseAction(state);
            AiAction action = resolveAction(actionIndex);

            // 3. 计算专属 Reward
            double recentVolume = repository.queryItemVolumeSince(item.getItemHash(), since);
            double imbalance = Math.abs(item.getCurrentInventory() - item.getTargetInventory())
                / (double) Math.max(1, item.getTargetInventory());
            double reward = (settings.rewardW1() * recentVolume)
                - (settings.rewardW2() * Math.abs(globalInflationRate))
                - (settings.rewardW3() * imbalance);

            // 4. 应用独立调控结果
            TuningResult result = applyActionToItem(item, action);
            
            // 5. 获取 nextState 并存入经验池
            ItemMarketRecord latest = repository.findByHash(item.getItemHash()).orElse(item);
            double nextSaturation = calculateSaturation(latest);
            double[] nextState = new double[] {nextSaturation, recentFlow, globalInflationRate};

            dqnTrainer.observe(state, actionIndex, reward, nextState);

            // 6. 日志输出规范：在 for 循环内部，分别打印每个物品独有的调控信息
            if (settings.debugLog()) {
                String hashShort = item.getItemHash().substring(0, Math.min(8, item.getItemHash().length()));
                String itemName = readableItemName(item);
                
                plugin.getLogger().info(String.format("[EcoBrain-AI] -> 调控目标: [%s] (Hash: %s)", itemName, hashShort));
                plugin.getLogger().info(String.format("    - 单品状态 (State) : 饱和度(current/target)=%.4f, 近期流速=%.4f, 全局通胀=%.6f", saturation, recentFlow, globalInflationRate));
                plugin.getLogger().info(String.format("    - 独立决策 (Action): %s", action.name()));
                plugin.getLogger().info(String.format("    - 专属反馈 (Reward): %.6f", reward));
                
                if (result == null) {
                    plugin.getLogger().info("    - 调控结果 (Result): HOLD，参数不变");
                } else {
                    String surgeTag = "";
                    if (result.surgeType() == SurgeType.SCARCITY_SURGE) {
                        surgeTag = " [稀缺暴涨!]";
                    } else if (result.surgeType() == SurgeType.GLUT_CRASH) {
                        surgeTag = " [爆仓暴跌!]";
                    }
                    plugin.getLogger().info(String.format(
                        "    - 调控结果 (Result): base_price [%.6f -> %.6f], k_factor [%.6f -> %.6f]%s",
                        result.oldBasePrice(),
                        result.newBasePrice(),
                        result.oldKFactor(),
                        result.newKFactor(),
                        surgeTag
                    ));
                }
            }
        }

        if (settings.debugLog()) {
            plugin.getLogger().info("[EcoBrain-AI] ===== 微观调控周期报告结束 =====");
        }

        // 统一在周期末尾训练本批次收集的独立经验
        dqnTrainer.trainBatch(settings.trainBatchSize());
    }

    private TuningResult applyActionToItem(ItemMarketRecord item, AiAction action) {
        if (action == AiAction.HOLD) {
            return null;
        }

        SurgeType surgeType = SurgeType.NONE;
        double oldBase = item.getBasePrice();
        double oldK = item.getKFactor();
        double newBasePrice = oldBase;
        double newK = oldK;

        if (action == AiAction.UP_PRICE) {
            if (fullSettings != null && item.getPhysicalStock() <= fullSettings.circuitBreaker().criticalInventory()) {
                surgeType = SurgeType.SCARCITY_SURGE;
                newBasePrice = oldBase * 2.0D;
                newK = clamp(oldK + 0.2D, settings.kMin(), settings.kMax());
            } else {
                double priceRate = settings.actionUpPriceRate();
                double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
                newBasePrice = clamp(oldBase * priceRate, oldBase * (1.0D - limit), oldBase * (1.0D + limit));
                newK = clamp(oldK + settings.kDelta(), settings.kMin(), settings.kMax());
            }
        } else if (action == AiAction.DOWN_PRICE) {
            if (item.getCurrentInventory() > item.getTargetInventory() * 5) {
                surgeType = SurgeType.GLUT_CRASH;
                newBasePrice = oldBase * 0.5D;
                newK = clamp(oldK - settings.kDelta(), settings.kMin(), settings.kMax());
            } else {
                double priceRate = settings.actionDownPriceRate();
                double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
                newBasePrice = clamp(oldBase * priceRate, oldBase * (1.0D - limit), oldBase * (1.0D + limit));
                newK = clamp(oldK - settings.kDelta(), settings.kMin(), settings.kMax());
            }
        }

        repository.updateTuning(item.getItemHash(), newBasePrice, newK);
        return new TuningResult(
            readableItemName(item),
            item.getItemHash(),
            oldBase,
            newBasePrice,
            oldK,
            newK,
            surgeType
        );
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

    private void logExplainableCycle() {
        // Obsolete, replaced by inline logging in tick()
    }

    private record TuningResult(String itemName, String hash, double oldBasePrice, double newBasePrice,
                                double oldKFactor, double newKFactor, SurgeType surgeType) {}
}
