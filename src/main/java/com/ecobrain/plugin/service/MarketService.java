package com.ecobrain.plugin.service;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeResult;
import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.safety.CircuitBreaker;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 市场业务服务：
 * - 封装 IPO 冷启动
 * - 封装买卖滑点计算
 * - 封装库存与成交记录落库
 */
public class MarketService {
    private final JavaPlugin plugin;
    private final ItemMarketRepository repository;
    private final AMMCalculator ammCalculator;
    private final CircuitBreaker circuitBreaker;
    private volatile PluginSettings.Economy economySettings;

    public MarketService(JavaPlugin plugin, ItemMarketRepository repository, AMMCalculator ammCalculator,
                         CircuitBreaker circuitBreaker, PluginSettings.Economy economySettings) {
        this.plugin = plugin;
        this.repository = repository;
        this.ammCalculator = ammCalculator;
        this.circuitBreaker = circuitBreaker;
        this.economySettings = economySettings;
    }

    /**
     * 热更新经济配置。
     */
    public void updateEconomySettings(PluginSettings.Economy economySettings) {
        this.economySettings = economySettings;
    }

    /**
     * 卖出场景下的 IPO 保证：
     * - 首次发现物品时，注入虚拟流动性（current_inventory = target_inventory）
     * - physical_stock 仅记录玩家真实卖入数量，不凭空增发实体库存
     * - zero-trust IPO 下，初始化价格极低，由玩家买单来发现价值
     */
    public CompletableFuture<IpoState> ensureIpoForSellAsync(String hash, String base64, int firstSellQuantity) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<ItemMarketRecord> existing = repository.findByHash(hash);
            if (existing.isPresent()) {
                return new IpoState(existing.get(), false);
            }
            // 自适应时，为了安全，将初始目标库存卡在物理库存的一个固定边界内。
            // 比如 16 或者当前真实卖出量的 2 倍，取其大者，防止一些奇怪的超大卖单直接把 target 拉满
            int dynamicTarget = Math.max(16, firstSellQuantity * 2);

            double initialBasePrice = economySettings.zeroTrustIpo() ? 100.0D : economySettings.ipoBasePrice();
            
            // 修复：IPO 时，virtualInitialInventory 应该等于 dynamicTarget，否则会导致开局饱和度异常
            // 比如卖 1000 个，dynamicTarget=2000，如果 virtualInitialInventory 还是 64，开局价格就会暴涨
            boolean insertedNow = repository.upsertIpo(hash, base64, initialBasePrice, economySettings.ipoKFactor(),
                dynamicTarget,
                dynamicTarget, // 这里原本是 virtualInitialInventory，现在改为 dynamicTarget，让初始饱和度为 100%
                Math.max(0, firstSellQuantity));
            ItemMarketRecord insertedRecord = repository.findByHash(hash)
                .orElseThrow(() -> new IllegalStateException("IPO insert failed"));
            return new IpoState(insertedRecord, insertedNow);
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    public TradeQuote quoteSell(ItemMarketRecord record, int amount) {
        if (!circuitBreaker.allowSell(record)) {
            throw new IllegalStateException("This item is frozen by circuit breaker");
        }
        // 科学 TWAP：从交易统计近似 time-weighted 平均价，用于 volatilitySpread
        long now = System.currentTimeMillis();
        int twapWindowHours = Math.max(1, plugin.getConfig().getInt("ai.aov-window-hours", 24));
        int bucketMinutes = Math.max(1, plugin.getConfig().getInt("ai.schedule-minutes", 15));
        long since = now - (long) twapWindowHours * 60L * 60L * 1000L;
        long bucketMs = (long) bucketMinutes * 60L * 1000L;
        double twap = repository.queryItemTwapSince(record.getItemHash(), since, bucketMs);
        if (twap <= 0.0D) {
            twap = ammCalculator.calculateCurrentPrice(record);
        }

        TradeResult result = ammCalculator.calculateSellTotal(record, amount, twap);
        return new TradeQuote(result.getTotalPrice(), result.getPostInventory(), TradeType.SELL);
    }

    public TradeQuote quoteBuy(ItemMarketRecord record, int amount) {
        if (record.getPhysicalStock() < amount) {
            throw new IllegalStateException("系统真实库存不足，无法出售！");
        }
        CircuitBreaker.BuyCheckResult check = circuitBreaker.checkBuy(record, amount);
        if (check != CircuitBreaker.BuyCheckResult.ALLOW) {
            String message = switch (check) {
                case FROZEN_BY_RISK -> "该物品当前处于风控冻结状态，暂不可买入。";
                case LOW_VIRTUAL_INVENTORY -> "该物品虚拟库存过低，暂不可买入。";
                case POST_BUY_STOCK_PROTECTED -> "该数量会触发库存保护，暂不可买入。";
                case ALLOW -> "系统繁忙，请稍后重试。";
            };
            throw new IllegalStateException(message);
        }
        TradeResult result = ammCalculator.calculateBuyTotal(record, amount);
        return new TradeQuote(result.getTotalPrice(), result.getPostInventory(), TradeType.BUY);
    }

    /**
     * 买入前的“库存预留”：
     * 通过原子扣减 physical_stock 防止并发超卖。成功后，调用方再进行扣款与发货。
     *
     * @return true 表示预留成功
     */
    public boolean reservePhysicalStockForBuy(String itemHash, int amount) {
        int critical = 0;
        try {
            critical = fullSettingsCriticalInventorySafe();
        } catch (Exception ignored) {
        }
        return repository.tryReservePhysicalStockForBuy(itemHash, amount, critical);
    }

    /**
     * 取消买入（扣款失败/背包不足/异常）时归还预留库存。
     */
    public void cancelReservedBuy(String itemHash, int amount) {
        repository.releaseReservedPhysicalStock(itemHash, amount);
    }

    /**
     * 预留成功后的买入结算：只更新虚拟库存池 + 记录成交，不再扣 physical_stock。
     */
    public void settleBuyAfterReservation(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount) {
        // 实时触发自适应目标库存（注意：预留模式下，真实库存已经在外层扣除，这里只需要基于新的状态更新 target）
        int newPhysical = record.getPhysicalStock() - amount;
        int oldTarget = record.getTargetInventory();
        int newTarget = oldTarget;
        try {
            PluginSettings settings = PluginSettings.load(plugin);
            if (settings.ai().adaptiveTarget().enabled()) {
                double smoothing = clamp01(settings.ai().adaptiveTarget().smoothingFactor());
                int cap = Math.max(1, settings.ai().adaptiveTarget().quantityCap());
                int m = Math.min(Math.max(1, amount), cap);
                // Quantity-aware EMA: alpha_eff = 1 - (1 - alpha)^m
                // This reacts faster to large batch trades while still doing only one DB write.
                double alphaEff = smoothing >= 1.0 ? 1.0 : (smoothing <= 0.0 ? 0.0 : (1.0 - Math.pow(1.0 - smoothing, m)));
                double ema = oldTarget + (newPhysical - oldTarget) * alphaEff;
                newTarget = (int) Math.round(ema);
                if (newTarget == oldTarget && newPhysical != oldTarget) {
                    newTarget += (newPhysical > oldTarget) ? 1 : -1;
                }
                newTarget = Math.max(1, newTarget);
                if (newTarget != oldTarget) {
                    repository.updateTargetInventoryWithProportionalCurrentScaling(
                        itemHash, oldTarget, quote.postInventory(), newTarget
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate real-time adaptive target for buy(reserve): " + e.getMessage());
        }

        if (newTarget == oldTarget) {
            repository.updateVirtualInventoryOnly(itemHash, quote.postInventory());
        }
        
        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }

        // Treasury: BUY means money flows into system treasury (income = expense model)
        if (quote.type() == TradeType.BUY) {
            repository.creditTreasuryCents(com.ecobrain.plugin.persistence.ItemMarketRepository.moneyToCents(quote.totalPrice()));
        }
    }

    private int fullSettingsCriticalInventorySafe() {
        // MarketService 本身不持有 PluginSettings 全量快照，这里以 config 读值为准：
        // - 调用发生在异步线程
        // - 该值会在 /ecobrain reload 后更新到 Bukkit config
        return plugin.getConfig().getInt("circuit-breaker.critical-inventory", 2);
    }

    /**
     * 卖出结算：
     * - 虚拟库存始终按 AMM 结算后库存写入
     * - 真实库存仅在非 IPO 建档首单时再追加数量（首单数量已在建档时入 physical_stock）
     */
    public void settleSell(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount, boolean ipoCreatedNow) {
        int newPhysical = ipoCreatedNow ? record.getPhysicalStock() : record.getPhysicalStock() + amount;
        
        // 实时触发自适应目标库存
        int oldTarget = record.getTargetInventory();
        int newTarget = oldTarget;
        try {
            PluginSettings settings = PluginSettings.load(plugin);
            if (settings.ai().adaptiveTarget().enabled()) {
                double smoothing = clamp01(settings.ai().adaptiveTarget().smoothingFactor());
                int cap = Math.max(1, settings.ai().adaptiveTarget().quantityCap());
                int m = Math.min(Math.max(1, amount), cap);
                double alphaEff = smoothing >= 1.0 ? 1.0 : (smoothing <= 0.0 ? 0.0 : (1.0 - Math.pow(1.0 - smoothing, m)));
                double ema = oldTarget + (newPhysical - oldTarget) * alphaEff;
                newTarget = (int) Math.round(ema);
                if (newTarget == oldTarget && newPhysical != oldTarget) {
                    newTarget += (newPhysical > oldTarget) ? 1 : -1;
                }
                newTarget = Math.max(1, newTarget);
                if (newTarget != oldTarget) {
                    repository.updateTargetInventoryWithProportionalCurrentScaling(
                        itemHash, oldTarget, quote.postInventory(), newTarget
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate real-time adaptive target for sell: " + e.getMessage());
        }
        
        // 如果 target 被实时缩放了，就不再用旧的 postInventory 覆盖
        if (newTarget == oldTarget) {
            repository.updateStocks(itemHash, quote.postInventory(), newPhysical);
        } else {
            // 只更新真实库存（虚拟库存已经在上面的 updateTargetInventoryWithProportionalCurrentScaling 里按比例更新了）
            repository.updatePhysicalStockOnly(itemHash, newPhysical);
        }
        
        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }
    }

    /**
     * 买入结算：虚拟库存与真实库存同步扣减。
     */
    public void settleBuy(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount) {
        int newPhysical = record.getPhysicalStock() - amount;
        
        // 实时触发自适应目标库存
        int oldTarget = record.getTargetInventory();
        int newTarget = oldTarget;
        try {
            PluginSettings settings = PluginSettings.load(plugin);
            if (settings.ai().adaptiveTarget().enabled()) {
                double smoothing = clamp01(settings.ai().adaptiveTarget().smoothingFactor());
                int cap = Math.max(1, settings.ai().adaptiveTarget().quantityCap());
                int m = Math.min(Math.max(1, amount), cap);
                double alphaEff = smoothing >= 1.0 ? 1.0 : (smoothing <= 0.0 ? 0.0 : (1.0 - Math.pow(1.0 - smoothing, m)));
                double ema = oldTarget + (newPhysical - oldTarget) * alphaEff;
                newTarget = (int) Math.round(ema);
                if (newTarget == oldTarget && newPhysical != oldTarget) {
                    newTarget += (newPhysical > oldTarget) ? 1 : -1;
                }
                newTarget = Math.max(1, newTarget);
                if (newTarget != oldTarget) {
                    repository.updateTargetInventoryWithProportionalCurrentScaling(
                        itemHash, oldTarget, quote.postInventory(), newTarget
                    );
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to calculate real-time adaptive target for buy: " + e.getMessage());
        }

        // 如果 target 被实时缩放了，就不再用旧的 postInventory 覆盖
        if (newTarget == oldTarget) {
            repository.updateStocks(itemHash, quote.postInventory(), newPhysical);
        } else {
            repository.updatePhysicalStockOnly(itemHash, newPhysical);
        }
        
        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }
    }

    public record TradeQuote(double totalPrice, int postInventory, TradeType type) {}
    public record IpoState(ItemMarketRecord record, boolean createdNow) {}

    private static double clamp01(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}
