package com.ecobrain.plugin.service;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.MarketSnapshot;
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
    private final StatisticalPriceDiscoveryService priceDiscoveryService;
    private final CircuitBreaker circuitBreaker;
    private final ItemOperationCoordinator itemOperationCoordinator;
    private volatile PluginSettings.Economy economySettings;

    public MarketService(JavaPlugin plugin, ItemMarketRepository repository, StatisticalPriceDiscoveryService priceDiscoveryService,
                         CircuitBreaker circuitBreaker, PluginSettings.Economy economySettings,
                         ItemOperationCoordinator itemOperationCoordinator) {
        this.plugin = plugin;
        this.repository = repository;
        this.priceDiscoveryService = priceDiscoveryService;
        this.circuitBreaker = circuitBreaker;
        this.economySettings = economySettings;
        this.itemOperationCoordinator = itemOperationCoordinator;
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
            // v5: 物品建档只记录身份与真实库存；定价从卡尔曼冷启动锚点开始，由玩家买单发现价值。
            boolean insertedNow = repository.upsertIpo(hash, base64, Math.max(0, firstSellQuantity));
            ItemMarketRecord insertedRecord = repository.findByHash(hash)
                .orElseThrow(() -> new IllegalStateException("IPO insert failed"));
            return new IpoState(insertedRecord, insertedNow);
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    public TradeQuote quoteSell(ItemMarketRecord record, int amount) {
        if (!circuitBreaker.allowSell(record)) {
            throw new IllegalStateException("This item is frozen by circuit breaker");
        }
        StatisticalPriceDiscoveryService.QuoteComputation computation = priceDiscoveryService.quoteSell(record, amount);
        return new TradeQuote(
            computation.totalPrice(),
            computation.compatibilityInventory(),
            TradeType.SELL,
            computation.snapshot()
        );
    }

    public TradeQuote quoteBuy(ItemMarketRecord record, int amount) {
        if (record.getPhysicalStock() < amount) {
            throw new IllegalStateException("系统真实库存不足，无法出售！");
        }
        CircuitBreaker.BuyCheckResult check = circuitBreaker.checkBuy(record, amount);
        if (check != CircuitBreaker.BuyCheckResult.ALLOW) {
            String message = switch (check) {
                case FROZEN_BY_RISK -> "该物品当前处于风控冻结状态，暂不可买入。";
                case LOW_VIRTUAL_INVENTORY -> "该物品当前市场深度异常，暂不可买入。";
                case POST_BUY_STOCK_PROTECTED -> "该数量会触发库存保护，暂不可买入。";
                case ALLOW -> "系统繁忙，请稍后重试。";
            };
            throw new IllegalStateException(message);
        }
        StatisticalPriceDiscoveryService.QuoteComputation computation = priceDiscoveryService.quoteBuy(record, amount);
        return new TradeQuote(
            computation.totalPrice(),
            computation.compatibilityInventory(),
            TradeType.BUY,
            computation.snapshot()
        );
    }

    public ItemOperationCoordinator.Permit acquireItemPermit(String itemHash) {
        return itemOperationCoordinator.acquire(itemHash);
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
     * 预留成功后的买入结算：physical_stock 已在预留阶段原子扣减，这里只记录成交并喂给滤波。
     */
    public void settleBuyAfterReservation(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount) {
        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }

        // Treasury: BUY means money flows into system treasury (income = expense model)
        if (quote.type() == TradeType.BUY) {
            repository.creditTreasuryCents(com.ecobrain.plugin.persistence.ItemMarketRepository.moneyToCents(quote.totalPrice()));
        }
        // 物理库存已在预留阶段扣减，直接构造成交后状态喂给滤波。
        ItemMarketRecord refreshed = record.withPhysicalStock(Math.max(0, record.getPhysicalStock() - amount));
        priceDiscoveryService.ingestTradeEvidence(
            refreshed,
            quote.type(),
            amount,
            quote.totalPrice(),
            player == null ? null : player.getUniqueId(),
            now
        );
    }

    private int fullSettingsCriticalInventorySafe() {
        // MarketService 本身不持有 PluginSettings 全量快照，这里以 config 读值为准：
        // - 调用发生在异步线程
        // - 该值会在 /ecobrain reload 后更新到 Bukkit config
        return plugin.getConfig().getInt("circuit-breaker.critical-inventory", 2);
    }

    /**
     * 卖出结算：真实库存增加（IPO 建档首单的数量已在建档时入库，避免重复计入）。
     */
    public void settleSell(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount, boolean ipoCreatedNow) {
        int newPhysical = ipoCreatedNow ? record.getPhysicalStock() : record.getPhysicalStock() + amount;
        repository.updatePhysicalStockOnly(itemHash, newPhysical);

        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }
        ItemMarketRecord refreshed = record.withPhysicalStock(newPhysical);
        priceDiscoveryService.ingestTradeEvidence(
            refreshed,
            quote.type(),
            amount,
            quote.totalPrice(),
            player == null ? null : player.getUniqueId(),
            now
        );
    }

    /**
     * 买入结算（非预留路径）：真实库存扣减。
     */
    public void settleBuy(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount) {
        int newPhysical = record.getPhysicalStock() - amount;
        repository.updatePhysicalStockOnly(itemHash, newPhysical);

        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }
        ItemMarketRecord refreshed = record.withPhysicalStock(newPhysical);
        priceDiscoveryService.ingestTradeEvidence(
            refreshed,
            quote.type(),
            amount,
            quote.totalPrice(),
            player == null ? null : player.getUniqueId(),
            now
        );
    }

    public MarketSnapshot snapshot(ItemMarketRecord record) {
        return priceDiscoveryService.snapshot(record);
    }

    public record TradeQuote(double totalPrice, int postInventory, TradeType type, MarketSnapshot snapshot) {}
    public record IpoState(ItemMarketRecord record, boolean createdNow) {}
}
