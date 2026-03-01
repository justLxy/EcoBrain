package com.ecobrain.plugin.placeholder;

import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家个人统计占位符缓存：
 * - 占位符解析阶段只读缓存，绝不阻塞主线程查库
 * - 若缓存过期则触发一次异步刷新（同一 UUID 去重）
 */
public final class PersonalStatsPlaceholderCache {
    public record Entry(double sellMoney, long sellQty, int sellRank, double buyMoney, long buyQty, int buyRank, long updatedAtMillis) {}

    private final Plugin plugin;
    private final ItemMarketRepository repository;
    private final long ttlMillis;

    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> inFlight = new ConcurrentHashMap<>();

    public PersonalStatsPlaceholderCache(Plugin plugin, ItemMarketRepository repository, long ttlMillis) {
        this.plugin = plugin;
        this.repository = repository;
        this.ttlMillis = Math.max(1000L, ttlMillis);
    }

    public Entry getOrSchedule(UUID uuid) {
        long now = System.currentTimeMillis();
        Entry current = cache.get(uuid);
        if (current == null) {
            current = new Entry(0.0D, 0L, 0, 0.0D, 0L, 0, 0L);
        }

        boolean stale = now - current.updatedAtMillis() > ttlMillis;
        if (!stale) {
            return current;
        }

        if (inFlight.putIfAbsent(uuid, true) == null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    double sellMoney = repository.getPlayerTotalMoney(uuid, TradeType.SELL);
                    long sellQty = repository.getPlayerTotalQuantity(uuid, TradeType.SELL);
                    int sellRank = repository.getPlayerRank(uuid, TradeType.SELL);
                    double buyMoney = repository.getPlayerTotalMoney(uuid, TradeType.BUY);
                    long buyQty = repository.getPlayerTotalQuantity(uuid, TradeType.BUY);
                    int buyRank = repository.getPlayerRank(uuid, TradeType.BUY);
                    cache.put(uuid, new Entry(sellMoney, sellQty, sellRank, buyMoney, buyQty, buyRank, System.currentTimeMillis()));
                } catch (Exception e) {
                    plugin.getLogger().warning("[PlaceholderAPI] 玩家个人统计刷新失败: " + e.getMessage());
                } finally {
                    inFlight.remove(uuid);
                }
            });
        }
        return current;
    }
}

