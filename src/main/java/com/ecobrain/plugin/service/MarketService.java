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
     * 异步 IPO 保证：
     * 如果 hash 不存在，按默认拓荒参数创建；存在则直接返回已有记录。
     */
    public CompletableFuture<ItemMarketRecord> ensureIpoAsync(String hash, String base64, int initialInventory) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<ItemMarketRecord> existing = repository.findByHash(hash);
            if (existing.isPresent()) {
                return existing.get();
            }
            repository.upsertIpo(hash, base64, economySettings.ipoBasePrice(), economySettings.ipoKFactor(),
                economySettings.ipoTargetInventory(),
                Math.max(1, initialInventory));
            return repository.findByHash(hash).orElseThrow(() -> new IllegalStateException("IPO insert failed"));
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
        if (!circuitBreaker.allowBuy(record)) {
            throw new IllegalStateException("This item is temporarily unavailable for buy");
        }
        TradeResult result = ammCalculator.calculateBuyTotal(record, amount);
        return new TradeQuote(result.getTotalPrice(), result.getPostInventory(), TradeType.BUY);
    }

    public void settle(String itemHash, TradeQuote quote, int amount) {
        repository.updateInventory(itemHash, quote.postInventory());
        repository.recordTrade(itemHash, quote.type(), amount, quote.totalPrice(), System.currentTimeMillis());
    }

    public record TradeQuote(double totalPrice, int postInventory, TradeType type) {}
}
