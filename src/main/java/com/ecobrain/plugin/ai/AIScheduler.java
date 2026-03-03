package com.ecobrain.plugin.ai;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.AMMCalculator;
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
 * 采用 ONNX PPO 模型实现连续动作调价。
 */
public class AIScheduler {
    private final JavaPlugin plugin;
    private final OnnxModelRunner onnxModelRunner;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;
    private final AMMCalculator ammCalculator;
    private volatile PluginSettings.AI settings;
    private BukkitTask task;

    private volatile PluginSettings fullSettings;

    public AIScheduler(JavaPlugin plugin, OnnxModelRunner onnxModelRunner,
                       ItemMarketRepository repository, AMMCalculator ammCalculator, 
                       PluginSettings.AI settings, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.onnxModelRunner = onnxModelRunner;
        this.repository = repository;
        this.ammCalculator = ammCalculator;
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

        int upCount = 0;
        int downCount = 0;

        for (ItemMarketRecord item : items) {
            // == 1. 动态自适应目标库存 ==
            if (settings.adaptiveTarget().enabled()) {
                int currentPhysical = item.getPhysicalStock();
                int oldTarget = item.getTargetInventory();
                double smoothing = settings.adaptiveTarget().smoothingFactor();
                
                // EMA 平滑靠近真实库存
                double ema = oldTarget + (currentPhysical - oldTarget) * smoothing;
                int newTarget = (int) Math.round(ema);
                
                // 防止取整导致卡死，如果还没达到物理库存，强制向其移动 1 步
                if (newTarget == oldTarget && currentPhysical != oldTarget) {
                    newTarget += (currentPhysical > oldTarget) ? 1 : -1;
                }
                newTarget = Math.max(1, newTarget);
                
                if (newTarget != oldTarget) {
                    repository.updateTargetInventoryWithProportionalCurrentScaling(
                        item.getItemHash(), oldTarget, item.getCurrentInventory(), newTarget
                    );
                    // 刷新内存里的 item 对象以供后续 AI 决策使用
                    item = repository.findByHash(item.getItemHash()).orElse(item);
                }
            }

            // 2. 获取当前状态 St
            double saturation = calculateSaturation(item);
            double recentFlow = repository.queryItemNetFlowBetween(item.getItemHash(), since, now) / windowMinutes;
            
            double currentPrice = ammCalculator.calculateCurrentPrice(item);
            // --- 科学 TWAP/Volatility ---
            long twapSince = now - aovWindowMs; // 复用 AOV 窗口长度作为 TWAP 窗口（可调）
            long bucketMs = Math.max(60_000L, windowMs); // 与调控周期同粒度的时间桶
            double twap = repository.queryItemTwapSince(item.getItemHash(), twapSince, bucketMs);
            if (twap <= 0.0D) {
                twap = currentPrice;
            }
            double volatility = twap > 0 ? Math.abs(currentPrice - twap) / twap : 0.0D;

            // --- 科学 Elasticity（启发式）---
            // 定义：
            // - 价格变化：使用“本周期成交均价”相对“上周期成交均价”的变化
            // - 数量变化：使用本周期净流速 recentFlow（buy pressure）作为需求强弱代理
            double unitPriceNow = repository.queryItemAvgUnitPriceBetween(item.getItemHash(), since, now);
            long prevFrom = Math.max(0L, since - windowMs);
            double unitPricePrev = repository.queryItemAvgUnitPriceBetween(item.getItemHash(), prevFrom, since);
            if (unitPriceNow <= 0.0D) {
                unitPriceNow = currentPrice;
            }
            if (unitPricePrev <= 0.0D) {
                unitPricePrev = twap > 0.0D ? twap : currentPrice;
            }
            double priceChangePct = unitPricePrev > 0.0D ? (unitPriceNow - unitPricePrev) / unitPricePrev : 0.0D;
            double denom = Math.max(1e-6, Math.abs(priceChangePct) * 100.0D);
            double elasticity = recentFlow / denom;
            // Clamp to avoid rare extreme explosions (keeps ONNX input stable)
            elasticity = Math.max(-1.0e4, Math.min(1.0e4, elasticity));
            // Keep observation semantics consistent with the Python simulator:
            // last feature = log(currentPrice) (clipped) rather than IPO flag.
            double logPrice = Math.log(Math.max(1e-9D, currentPrice));
            logPrice = clamp(logPrice, -20.0D, 20.0D);

            float[] obs = new float[] {
                (float) saturation,
                (float) recentFlow,
                (float) globalInflationRate,
                (float) elasticity,
                (float) volatility,
                (float) logPrice
            };

            // 2. 只有最近有交易才由 AI 处理，否则跳过
            double recentVolume = repository.queryItemVolumeSince(item.getItemHash(), since);
            boolean hasRecentTrade = recentVolume > 0.0D;

            // 4. 为【当前周期】做决策 At
            double[] action = new double[]{1.0, 0.0}; // [basePriceMultiplier, kDelta]
            String valueType;
            if (item.getBasePrice() >= settings.tiers().highPriceThreshold() || item.getTargetInventory() <= settings.tiers().highInventoryThreshold()) {
                valueType = "high";
            } else if (item.getBasePrice() >= settings.tiers().midPriceThreshold() || item.getTargetInventory() <= settings.tiers().midInventoryThreshold()) {
                valueType = "mid";
            } else {
                valueType = "low";
            }
            if (hasRecentTrade) {
                PluginSettings.TierTuning tuning = tierTuningFor(valueType);
                action = onnxModelRunner.predictAction(obs, valueType, tuning.kDelta());
            }

            String tuningReason = "AI_ACTION";
            if (!hasRecentTrade) {
                tuningReason = "NO_RECENT_TRADE";
            }

            // 5. 应用最终动作
            TuningResult result = applyActionToItem(item, action, valueType);

            if (result != null) {
                String actionName = String.format("MULT:%.2f K:%.2f", action[0], action[1]);
                repository.recordAiTuningEvent(
                    item.getItemHash(),
                    actionName,
                    tuningReason,
                    result.oldBasePrice(),
                    result.newBasePrice(),
                    result.oldKFactor(),
                    result.newKFactor(),
                    0.0, // Reward is calculated offline in 2.0
                    now
                );
            }

            // 修正输出：有些价格已经达到 maxBasePrice，其实没涨，过滤掉
            // 如果 oldBasePrice 接近 maxBasePrice，并且 newBasePrice 也接近 maxBasePrice，说明其实没涨
            boolean isAtMax = result != null && result.newBasePrice() >= settings.maxBasePrice() * 0.99 && result.oldBasePrice() >= settings.maxBasePrice() * 0.99;
            boolean priceChanged = result != null && Math.abs(result.oldBasePrice() - result.newBasePrice()) > 1e-5;
            
            if (!isAtMax && priceChanged) {
                if (result.newBasePrice() > result.oldBasePrice() + 1e-6) {
                    upCount++;
                } else if (result.newBasePrice() < result.oldBasePrice() - 1e-6) {
                    downCount++;
                }
            }
            
            // 7. 日志输出规范：在 for 循环内部，分别打印每个物品独有的调控信息
            if (settings.debugLog()) {
                String hashShort = item.getItemHash().substring(0, Math.min(8, item.getItemHash().length()));
                String itemName = readableItemName(item);
                
                plugin.getLogger().info(String.format("[EcoBrain-AI] -> 调控目标: [%s] (Hash: %s)", itemName, hashShort));
                plugin.getLogger().info(String.format("    - 单品状态 (State) : 饱和度(current/target)=%.4f, 近期流速=%.4f, 全局通胀=%.6f", saturation, recentFlow, globalInflationRate));
                plugin.getLogger().info(String.format("    - 独立决策 (Action): MULT=%.3f, K=%.3f", action[0], action[1]));
                
                if (result == null) {
                    plugin.getLogger().info("    - 调控结果 (Result): HOLD，参数不变");
                } else {
                    plugin.getLogger().info(String.format(
                        "    - 调控结果 (Result): base_price [%.6f -> %.6f], k_factor [%.6f -> %.6f]",
                        result.oldBasePrice(),
                        result.newBasePrice(),
                        result.oldKFactor(),
                        result.newKFactor()
                    ));
                }
            }
        }

        if (settings.debugLog()) {
            plugin.getLogger().info("[EcoBrain-AI] ===== 微观调控周期报告结束 =====");
        }

        if (upCount > 0 || downCount > 0) {
            StringBuilder message = new StringBuilder();
            message.append("&8[&6EcoBrain&8] &b市场调控完毕&7: &a").append(upCount).append("&7 个物品价格上涨，&c").append(downCount).append("&7 个物品价格下跌。&e(输入 /ecobrain 查看)");
            
            String finalMessage = ChatColor.translateAlternateColorCodes('&', message.toString());
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getServer().broadcastMessage(finalMessage);
            });
        }

        // 统一在周期末尾训练本批次收集的独立经验
        // DQN 时代： dqnTrainer.trainBatch(settings.trainBatchSize());
        // PPO 时代： 离线外置模型，无需实时训练
    }

    private PluginSettings.TierTuning tierTuningFor(String valueType) {
        if (valueType == null) {
            return settings.tiers().low().tuning();
        }
        return switch (valueType) {
            case "high" -> settings.tiers().high().tuning();
            case "mid" -> settings.tiers().mid().tuning();
            default -> settings.tiers().low().tuning();
        };
    }

    /**
     * Tier-aware cap for base_price to match the simulator's hard ranges:
     * - low:  <= mid.price-threshold (default 1000)
     * - mid:  <= high.price-threshold (default 10000)
     * - high: <= global max-base-price
     */
    private double tierBasePriceCap(String valueType) {
        double global = settings.maxBasePrice();
        if (valueType == null) {
            return global;
        }
        return switch (valueType) {
            case "low" -> Math.min(global, settings.tiers().midPriceThreshold());
            case "mid" -> Math.min(global, settings.tiers().highPriceThreshold());
            default -> global;
        };
    }

    private TuningResult applyActionToItem(ItemMarketRecord item, double[] action, String valueType) {
        // --- 防垃圾回收机制（按时间过期） ---
        // 解释：如果有玩家随便附魔了一把木剑（物理库存=1）卖给系统，但之后根本没人买（交易量为0）。
        // 这种东西如果不处理，就会永远卡在 GUI 里。
        // 现在我们不看价格，看时间：如果这个物品距离最后一次交易已经超过了 7 天（即长时间无人问津），
        // 并且它在系统里只有少量的库存（<= 1），我们就判定它是“滞销垃圾”，自动销毁档案。
        if (item.getPhysicalStock() <= 1) {
            long lastTrade = repository.queryLastTradeTime(item.getItemHash());
            if (lastTrade <= 0L) {
                // 无任何交易记录时：改用建档时间兜底清理。
                // 这样玩家上传的“独一无二”但无人问津的物品不会永久残留在数据库/GUI。
                long createdAt = item.getCreatedAtMillis();
                if (createdAt > 0L) {
                    long daysSinceCreated = (System.currentTimeMillis() - createdAt) / (1000L * 60 * 60 * 24);
                    if (daysSinceCreated >= settings.garbageCollectionDays()) {
                        repository.deleteByHash(item.getItemHash());
                        return null;
                    }
                }
                // 极端容错：若历史数据 created_at=0（例如更老的库），则不做自动清理，避免误删。
            }
            long daysSinceLastTrade = (System.currentTimeMillis() - lastTrade) / (1000L * 60 * 60 * 24);
            if (lastTrade > 0L && daysSinceLastTrade >= settings.garbageCollectionDays()) {
                repository.deleteByHash(item.getItemHash());
                return null;
            }
        }

        // 如果本轮是 HOLD（没有任何调参动作），则不改参数；但上面的垃圾回收仍然需要执行。
        if (Math.abs(action[0] - 1.0D) < 1e-5 && Math.abs(action[1]) < 1e-5) {
            return null;
        }

        PluginSettings.TierTuning tierTuning = tierTuningFor(valueType);

        double oldBase = item.getBasePrice();
        double oldK = item.getKFactor();
        double newBasePrice = oldBase;
        double newK = oldK;

        // Apply ONNX continuous action (pure RL): only hard clamps remain.
        double priceMult = action[0];
        double kDelta = action[1];
        double limit = Math.max(0.0D, settings.perCycleMaxChangePercent());
        // Clip the AI multiplier to the safe per-cycle max change percent
        double safeMult = clamp(priceMult, 1.0D - limit, 1.0D + limit);
        newBasePrice = oldBase * safeMult;
        newBasePrice = clamp(newBasePrice, 0.01D, tierBasePriceCap(valueType));

        // Limit K factor changes
        double safeKDelta = clamp(kDelta, -tierTuning.kDelta(), tierTuning.kDelta());
        newK = clamp(oldK + safeKDelta, tierTuning.kMin(), tierTuning.kMax());

        // 若 clamp 后没有产生任何实际变更，则不写库、不落审计事件，避免噪声与无意义 IO
        boolean unchanged = Math.abs(newBasePrice - oldBase) < 1e-12 && Math.abs(newK - oldK) < 1e-12;
        if (unchanged) {
            return null;
        }

        repository.updateTuning(item.getItemHash(), newBasePrice, newK);
        return new TuningResult(
            item.getItemHash(),
            oldBase,
            newBasePrice,
            oldK,
            newK
        );
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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
    private record ItemNameInfo(String name, boolean hasCustomDisplayName) {}

    private ItemNameInfo itemNameInfo(ItemMarketRecord record) {
        try {
            ItemStack itemStack = itemSerializer.deserializeFromBase64(record.getItemBase64());
            ItemMeta meta = itemStack.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                return new ItemNameInfo(ChatColor.stripColor(meta.getDisplayName()), true);
            }
            if (itemStack.getType() != null) {
                return new ItemNameInfo(itemStack.getType().name(), false);
            }
        } catch (Exception ignored) {
        }
        return new ItemNameInfo("Unknown Item", false);
    }

    private ItemNameInfo itemNameInfoSync(ItemMarketRecord record) {
        try {
            return Bukkit.getScheduler().callSyncMethod(plugin, () -> itemNameInfo(record)).get();
        } catch (Exception ignored) {
            return new ItemNameInfo("Unknown Item", false);
        }
    }

    private String readableItemName(ItemMarketRecord record) {
        return itemNameInfo(record).name();
    }

    private void logExplainableCycle() {
        // Obsolete, replaced by inline logging in tick()
    }

    private record TuningResult(String hash, double oldBasePrice, double newBasePrice,
                                double oldKFactor, double newKFactor) {}
}
