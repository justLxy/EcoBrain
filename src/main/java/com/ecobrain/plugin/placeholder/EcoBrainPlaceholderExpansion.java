package com.ecobrain.plugin.placeholder;

import com.ecobrain.plugin.model.PlayerStat;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EcoBrainPlaceholderExpansion extends PlaceholderExpansion {
    private static final Pattern TOP_PATTERN = Pattern.compile("^(?:top|lb|leaderboard)_(sell|buy)_(name|money)_(\\d{1,3})(?:_(raw))?$");

    private final Plugin plugin;
    private final LeaderboardPlaceholderCache cache;
    private final PersonalStatsPlaceholderCache personalCache;

    public EcoBrainPlaceholderExpansion(Plugin plugin, LeaderboardPlaceholderCache cache, PersonalStatsPlaceholderCache personalCache) {
        this.plugin = plugin;
        this.cache = cache;
        this.personalCache = personalCache;
    }

    @Override
    public String getIdentifier() {
        return "ecobrain";
    }

    @Override
    public String getAuthor() {
        List<String> authors = plugin.getDescription().getAuthors();
        if (authors == null || authors.isEmpty()) {
            return "EcoBrain";
        }
        return String.join(", ", authors);
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (params == null || params.isBlank()) {
            return "";
        }

        if ("leaderboard_updated_ms".equalsIgnoreCase(params) || "lb_updated_ms".equalsIgnoreCase(params)) {
            return Long.toString(cache.snapshot().updatedAtMillis());
        }

        String lower = params.toLowerCase(Locale.ROOT);
        if (lower.startsWith("self_") || lower.startsWith("me_")) {
            if (player == null || player.getUniqueId() == null) {
                return "";
            }
            PersonalStatsPlaceholderCache.Entry entry = personalCache.getOrSchedule(player.getUniqueId());

            boolean raw = lower.endsWith("_raw");
            String key = lower;
            if (raw) {
                key = key.substring(0, key.length() - 4);
            }

            return switch (key) {
                case "self_sell_money", "me_sell_money" -> raw ? Double.toString(entry.sellMoney()) : String.format(Locale.US, "%.2f", entry.sellMoney());
                case "self_buy_money", "me_buy_money" -> raw ? Double.toString(entry.buyMoney()) : String.format(Locale.US, "%.2f", entry.buyMoney());
                case "self_sell_qty", "me_sell_qty" -> Long.toString(entry.sellQty());
                case "self_buy_qty", "me_buy_qty" -> Long.toString(entry.buyQty());
                case "self_sell_rank", "me_sell_rank" -> Integer.toString(entry.sellRank());
                case "self_buy_rank", "me_buy_rank" -> Integer.toString(entry.buyRank());
                case "self_updated_ms", "me_updated_ms" -> Long.toString(entry.updatedAtMillis());
                default -> null;
            };
        }

        Matcher m = TOP_PATTERN.matcher(params.toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }

        String side = m.group(1); // sell / buy
        String field = m.group(2); // name / money
        int rank;
        try {
            rank = Integer.parseInt(m.group(3));
        } catch (NumberFormatException e) {
            return "";
        }
        boolean raw = m.group(4) != null;

        if (rank <= 0) {
            return "";
        }

        LeaderboardPlaceholderCache.Snapshot snap = cache.snapshot();
        List<PlayerStat> list = "sell".equals(side) ? snap.topSellers() : snap.topBuyers();
        int idx = rank - 1;
        PlayerStat stat = (idx >= 0 && idx < list.size()) ? list.get(idx) : null;

        if ("name".equals(field)) {
            return stat == null ? "" : (stat.playerName() == null ? "" : stat.playerName());
        }

        if (stat == null) {
            return raw ? "0" : "0.00";
        }
        double money = stat.totalMoney();
        return raw ? Double.toString(money) : String.format(Locale.US, "%.2f", money);
    }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return onRequest(player, params);
    }
}

