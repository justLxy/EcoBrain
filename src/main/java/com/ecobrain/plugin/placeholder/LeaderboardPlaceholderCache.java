package com.ecobrain.plugin.placeholder;

import com.ecobrain.plugin.model.PlayerStat;
import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

/**
 * 为 PlaceholderAPI 提供的轻量排行榜缓存：
 * - 仅在异步线程刷新数据库数据
 * - 占位符解析阶段只读内存快照，避免主线程卡顿
 */
public final class LeaderboardPlaceholderCache {
    public record Snapshot(List<PlayerStat> topSellers, List<PlayerStat> topBuyers, long updatedAtMillis) {}

    private final Plugin plugin;
    private final ItemMarketRepository repository;
    private final int limit;
    private final long refreshPeriodTicks;

    private volatile Snapshot snapshot = new Snapshot(List.of(), List.of(), 0L);
    private BukkitTask task;

    public LeaderboardPlaceholderCache(Plugin plugin, ItemMarketRepository repository, int limit, long refreshPeriodTicks) {
        this.plugin = plugin;
        this.repository = repository;
        this.limit = Math.max(1, limit);
        this.refreshPeriodTicks = Math.max(20L, refreshPeriodTicks);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public synchronized void start() {
        if (task != null) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::refreshOnceSafe);
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::refreshOnceSafe, refreshPeriodTicks, refreshPeriodTicks);
    }

    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void refreshOnceSafe() {
        try {
            List<PlayerStat> topSellers = repository.getTopPlayers(TradeType.SELL, limit);
            List<PlayerStat> topBuyers = repository.getTopPlayers(TradeType.BUY, limit);
            snapshot = new Snapshot(List.copyOf(topSellers), List.copyOf(topBuyers), System.currentTimeMillis());
        } catch (Exception e) {
            plugin.getLogger().warning("[PlaceholderAPI] 排行榜缓存刷新失败: " + e.getMessage());
        }
    }
}

