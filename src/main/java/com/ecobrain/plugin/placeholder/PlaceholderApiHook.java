package com.ecobrain.plugin.placeholder;

import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * PlaceholderAPI 可选集成入口（softdepend）。
 */
public final class PlaceholderApiHook {
    private final Plugin plugin;
    private final LeaderboardPlaceholderCache cache;
    private final PersonalStatsPlaceholderCache personalCache;
    private EcoBrainPlaceholderExpansion expansion;

    public PlaceholderApiHook(Plugin plugin, ItemMarketRepository repository) {
        this.plugin = plugin;
        this.cache = new LeaderboardPlaceholderCache(plugin, repository, 10, 100L);
        this.personalCache = new PersonalStatsPlaceholderCache(plugin, repository, 5000L);
    }

    public void registerIfPresent() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return;
        }
        cache.start();
        this.expansion = new EcoBrainPlaceholderExpansion(plugin, cache, personalCache);
        boolean ok = this.expansion.register();
        if (ok) {
            plugin.getLogger().info("[PlaceholderAPI] 已注册占位符: %" + expansion.getIdentifier() + "_...%");
        } else {
            plugin.getLogger().warning("[PlaceholderAPI] 占位符注册失败。");
        }
    }

    public void shutdown() {
        try {
            if (expansion != null) {
                expansion.unregister();
            }
        } catch (Exception ignored) {
        } finally {
            cache.stop();
        }
    }
}

