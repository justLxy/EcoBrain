package com.ecobrain.plugin.service;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeResult;
import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.safety.CircuitBreaker;
import org.bukkit.plugin.java.JavaPlugin;

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
            // 比如 64 或者当前真实卖出量的 2 倍，取其大者，防止一些奇怪的超大卖单直接把 target 拉满
            int virtualInitialInventory = Math.max(1, economySettings.ipoTargetInventory());
            int dynamicTarget = Math.max(economySettings.ipoTargetInventory(), firstSellQuantity * 2);

            double initialBasePrice = economySettings.zeroTrustIpo() ? 0.01D : economySettings.ipoBasePrice();
            
            // 修复：IPO 时，virtualInitialInventory 应该等于 dynamicTarget，否则会导致开局饱和度异常
            // 比如卖 1000 个，dynamicTarget=2000，如果 virtualInitialInventory 还是 64，开局价格就会暴涨
            repository.upsertIpo(hash, base64, initialBasePrice, economySettings.ipoKFactor(),
                dynamicTarget,
                dynamicTarget, // 这里原本是 virtualInitialInventory，现在改为 dynamicTarget，让初始饱和度为 100%
                Math.max(0, firstSellQuantity));
            ItemMarketRecord inserted = repository.findByHash(hash)
                .orElseThrow(() -> new IllegalStateException("IPO insert failed"));
            return new IpoState(inserted, true);
        }, runnable -> plugin.getServer().getScheduler().runTaskAsynchronously(plugin, runnable));
    }

    public TradeQuote quoteSell(ItemMarketRecord record, int amount) {
        if (!circuitBreaker.allowSell(record)) {
            throw new IllegalStateException("This item is frozen by circuit breaker");
        }
        TradeResult result = ammCalculator.calculateSellTotal(record, amount);
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
     * 卖出结算：
     * - 虚拟库存始终按 AMM 结算后库存写入
     * - 真实库存仅在非 IPO 建档首单时再追加数量（首单数量已在建档时入 physical_stock）
     */
    public void settleSell(org.bukkit.entity.Player player, String itemHash, ItemMarketRecord record, TradeQuote quote, int amount, boolean ipoCreatedNow) {
        int newPhysical = ipoCreatedNow ? record.getPhysicalStock() : record.getPhysicalStock() + amount;
        repository.updateStocks(itemHash, quote.postInventory(), newPhysical);
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
        repository.updateStocks(itemHash, quote.postInventory(), newPhysical);
        long now = System.currentTimeMillis();
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), now);
        if (player != null) {
            repository.recordPlayerTransaction(player.getUniqueId(), player.getName(), quote.type(), itemHash, amount, quote.totalPrice(), now);
        }
    }

    public record TradeQuote(double totalPrice, int postInventory, TradeType type) {}
    public record IpoState(ItemMarketRecord record, boolean createdNow) {}
}
