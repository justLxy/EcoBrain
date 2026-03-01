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

    private static class AgentMemory {
        final double[] state;
        final int actionIndex;

        AgentMemory(double[] state, int actionIndex) {
            this.state = state;
            this.actionIndex = actionIndex;
        }
    }
    private final java.util.concurrent.ConcurrentHashMap<String, AgentMemory> memoryBank = new java.util.concurrent.ConcurrentHashMap<>();

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

        long aovWindowMs = Math.max(1, settings.aovWindowHours()) * 60L * 60L * 1000L;
        long aovSince = now - aovWindowMs;
        double dynamicAov = repository.queryDynamicAovSince(aovSince);
        if (dynamicAov <= 0.0D) {
            if (fullSettings != null) {
                // 回退机制：如果过去没有任何一笔交易，则使用 IPO 基础底价的 20 倍作为假定客单价
                dynamicAov = fullSettings.economy().ipoBasePrice() * 20.0D;
            } else {
                // 极端容错：如果连 fullSettings 都没加载出来，给一个绝对安全的低倍率值防崩溃
                dynamicAov = 1000.0D;
            }
        }

        double cycleNetEmission = repository.queryNetEmissionSince(since);
        double globalInflationRate = cycleNetEmission / dynamicAov;

        if (settings.debugLog()) {
            plugin.getLogger().info("[EcoBrain-AI] ===== 微观调控周期报告开始 =====");
            plugin.getLogger().info(String.format("[EcoBrain-AI] 全局宏观状态: %dh动态客单价(基准) = %.2f 金币", settings.aovWindowHours(), dynamicAov));
            plugin.getLogger().info(String.format("[EcoBrain-AI] 全局特征提取 -> 周期净印发=%.2f, global_inflation=%.6f", cycleNetEmission, globalInflationRate));
        }

        for (ItemMarketRecord item : items) {
            // 1. 获取当前状态 St
            double saturation = calculateSaturation(item);
            double recentFlow = repository.queryItemNetFlowSince(item.getItemHash(), since) / windowMinutes;
            double[] currentState = new double[] {saturation, recentFlow, globalInflationRate};

            // 2. 获取针对【上一个周期】的反馈 (Reward Rt)
            double recentVolume = repository.queryItemVolumeSince(item.getItemHash(), since);
            double normalizedVolume = recentVolume / Math.max(1.0, dynamicAov);

            double imbalance = Math.abs(item.getCurrentInventory() - item.getTargetInventory())
                / (double) Math.max(1, item.getTargetInventory());
                
            double reward = (settings.rewardW1() * normalizedVolume)
                - (settings.rewardW2() * Math.abs(globalInflationRate))
                - (settings.rewardW3() * imbalance);

            // 3. 闭环：将 (S_{t-1}, A_{t-1}, R_t, S_t) 存入经验池
            AgentMemory lastMemory = memoryBank.get(item.getItemHash());
            if (lastMemory != null) {
                dqnTrainer.observe(lastMemory.state, lastMemory.actionIndex, reward, currentState);
            }

            // 4. 为【当前周期】做决策 At
            int actionIndex = dqnTrainer.chooseAction(currentState);
            AiAction action = resolveAction(actionIndex);

            // 【强制风控拦截】：爆仓与稀缺保护应无视且覆盖 AI 的错误探索动作
            // 修正判定：只有当 target_inventory 足够大时，才用 5 倍。如果 target 只有几十，很容易超过。
            // 我们同时加入一个绝对差值的下限，防止刚上市的小物品瞬间爆仓。
            boolean isGlut = item.getCurrentInventory() > Math.max(item.getTargetInventory() * 5, 500);
            
            // 如果 config 里设为了 0，那么只有当库存真实 <= 0 时，才会被判定为稀缺并触发暴涨。
            boolean isScarcity = fullSettings != null && item.getPhysicalStock() <= fullSettings.circuitBreaker().criticalInventory();

            if (isGlut) {
                action = AiAction.DOWN_PRICE;
                actionIndex = 0; // DOWN_PRICE index
            } else if (isScarcity) {
                action = AiAction.UP_PRICE;
                actionIndex = 1; // UP_PRICE index
            }

            // 5. 记忆本次决策，留给下个周期作为 A_{t-1} 评估
            memoryBank.put(item.getItemHash(), new AgentMemory(currentState, actionIndex));

            // 6. 应用最终动作
            TuningResult result = applyActionToItem(item, action, isGlut, isScarcity);
            
            if (result != null && Math.abs(result.oldBasePrice() - result.newBasePrice()) > 0.001 && !result.itemName().contains("[过期销毁]")) {
                String direction = result.newBasePrice() > result.oldBasePrice() ? "上涨" : "下跌";
                String itemName = result.itemName();
                double newPrice = result.newBasePrice();
                
                String surgeTag = "";
                if (result.surgeType() == SurgeType.SCARCITY_SURGE) {
                    surgeTag = " &c[稀缺暴涨!]";
                } else if (result.surgeType() == SurgeType.GLUT_CRASH) {
                    surgeTag = " &a[爆仓暴跌!]";
                }
                
                String command = String.format("bc &8[&6EcoBrain&8] &b市场调控&7: &f%s &7的基准价格已%s至 &e%.2f &7金币%s", 
                        itemName, direction, newPrice, surgeTag);
                        
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command);
                });
            }
            
            // 7. 日志输出规范：在 for 循环内部，分别打印每个物品独有的调控信息
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

    private TuningResult applyActionToItem(ItemMarketRecord item, AiAction action, boolean isGlut, boolean isScarcity) {
        if (action == AiAction.HOLD && !isGlut && !isScarcity) {
            return null;
        }

        SurgeType surgeType = SurgeType.NONE;
        double oldBase = item.getBasePrice();
        double oldK = item.getKFactor();
        double newBasePrice = oldBase;
        double newK = oldK;

        if (isGlut) {
            surgeType = SurgeType.GLUT_CRASH;
            newBasePrice = Math.max(0.01, oldBase * 0.8D);
            newK = clamp(oldK - 0.1D, settings.kMin(), settings.kMax());
        } else if (isScarcity) {
            surgeType = SurgeType.SCARCITY_SURGE;
            newBasePrice = oldBase * 2.0D;
            newK = clamp(oldK + 0.1D, settings.kMin(), settings.kMax());
        } else {
            if (action == AiAction.UP_PRICE) {
                double priceRate = settings.actionUpPriceRate();
                double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
                newBasePrice = clamp(oldBase * priceRate, oldBase * (1.0D - limit), oldBase * (1.0D + limit));
                newK = clamp(oldK + settings.kDelta(), settings.kMin(), settings.kMax());
            } else if (action == AiAction.DOWN_PRICE) {
                double priceRate = settings.actionDownPriceRate();
                double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
                newBasePrice = clamp(oldBase * priceRate, oldBase * (1.0D - limit), oldBase * (1.0D + limit));
                newBasePrice = Math.max(0.01, newBasePrice);
                newK = clamp(oldK - settings.kDelta(), settings.kMin(), settings.kMax());
            }
        }

        // --- 防垃圾回收机制（按时间过期） ---
        // 解释：如果有玩家随便附魔了一把木剑（物理库存=1）卖给系统，但之后根本没人买（交易量为0）。
        // 这种东西如果不处理，就会永远卡在 GUI 里。
        // 现在我们不看价格，看时间：如果这个物品距离最后一次交易已经超过了 7 天（即长时间无人问津），
        // 并且它在系统里只有少量的库存（<= 1），我们就判定它是“滞销垃圾”，自动销毁档案。
        if (item.getPhysicalStock() <= 1) {
            long lastTrade = repository.queryLastTradeTime(item.getItemHash());
            long daysSinceLastTrade = (System.currentTimeMillis() - lastTrade) / (1000L * 60 * 60 * 24);
            if (daysSinceLastTrade >= 7) {
                repository.deleteByHash(item.getItemHash());
                return new TuningResult(readableItemName(item) + " [过期销毁]", item.getItemHash(), oldBase, 0, oldK, 0, SurgeType.NONE);
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
