package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.safety.CircuitBreaker;
import com.ecobrain.plugin.service.AMMCalculator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主手市场提示：
 * - 玩家主手切换到“系统市场已收录且可买入”的物品时，发送一次 Title/副Title
 * - 对同一 item_hash 持续手持不重复提示；切换到新物品会提示新物品
 * - 使用延迟任务做防抖，避免滚轮快速切换时刷屏
 */
public final class MarketHandHintListener implements Listener {
    private final Plugin plugin;
    private final ItemMarketRepository repository;
    private final ItemSerializer itemSerializer;
    private final AMMCalculator ammCalculator;
    private final CircuitBreaker circuitBreaker;

    private final ConcurrentHashMap<UUID, BukkitTask> debounceTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastShownHash = new ConcurrentHashMap<>();

    public MarketHandHintListener(Plugin plugin,
                                  ItemMarketRepository repository,
                                  ItemSerializer itemSerializer,
                                  AMMCalculator ammCalculator,
                                  CircuitBreaker circuitBreaker) {
        this.plugin = plugin;
        this.repository = repository;
        this.itemSerializer = itemSerializer;
        this.ammCalculator = ammCalculator;
        this.circuitBreaker = circuitBreaker;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        scheduleDebounced(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        scheduleDebounced(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        // Only react when click can affect currently selected hotbar slot (0..8).
        int heldSlot = player.getInventory().getHeldItemSlot();
        int raw = event.getRawSlot();
        if (raw == heldSlot || (raw >= 0 && raw <= 8)) {
            scheduleDebounced(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        BukkitTask task = debounceTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        lastShownHash.remove(id);
    }

    private void scheduleDebounced(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!plugin.getConfig().getBoolean("market-hint.enabled", true)) {
            return;
        }
        int debounceTicks = Math.max(0, plugin.getConfig().getInt("market-hint.debounce-ticks", 4));
        UUID id = player.getUniqueId();
        BukkitTask old = debounceTasks.remove(id);
        if (old != null) {
            old.cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            debounceTasks.remove(id);
            evaluateAndMaybeSend(player);
        }, debounceTicks);
        debounceTasks.put(id, task);
    }

    private void evaluateAndMaybeSend(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!plugin.getConfig().getBoolean("market-hint.enabled", true)) {
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            lastShownHash.remove(player.getUniqueId());
            return;
        }
        if (hand.getMaxStackSize() <= 1) {
            lastShownHash.remove(player.getUniqueId());
            return;
        }

        ItemStack snapshot = hand.clone().asOne();
        UUID playerId = player.getUniqueId();
        boolean requireBuyable = plugin.getConfig().getBoolean("market-hint.require-buyable", true);
        int criticalLimit = plugin.getConfig().getInt("circuit-breaker.critical-inventory", 2);

        String title = colorize(plugin.getConfig().getString(
            "market-hint.title",
            "&7你感受到一笔&f交易机会"
        ));
        String cmd = plugin.getConfig().getString(
            "market-hint.command",
            "/ecobrain buy <数量>"
        );
        String subtitleTemplate = plugin.getConfig().getString(
            "market-hint.subtitle",
            "&f{item} &7在市场有货：&e{price}&7｜&b{cmd}"
        );
        int fadeIn = Math.max(0, plugin.getConfig().getInt("market-hint.fade-in-ticks", 5));
        int stay = Math.max(0, plugin.getConfig().getInt("market-hint.stay-ticks", 30));
        int fadeOut = Math.max(0, plugin.getConfig().getInt("market-hint.fade-out-ticks", 5));

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String base64 = itemSerializer.serializeToBase64(snapshot);
                String hash = itemSerializer.sha256(base64);

                String already = lastShownHash.get(playerId);
                if (hash.equals(already)) {
                    return;
                }

                Optional<ItemMarketRecord> optional = repository.findByHash(hash);
                if (optional.isEmpty()) {
                    lastShownHash.remove(playerId);
                    return;
                }
                ItemMarketRecord record = optional.get();
                if (record.getPhysicalStock() <= 0) {
                    lastShownHash.remove(playerId);
                    return;
                }
                if (requireBuyable) {
                    if (record.getPhysicalStock() <= criticalLimit) {
                        lastShownHash.remove(playerId);
                        return;
                    }
                    if (!circuitBreaker.allowBuy(record, 1)) {
                        lastShownHash.remove(playerId);
                        return;
                    }
                }

                double price = ammCalculator.calculateCurrentPrice(record);
                String priceText = String.format(Locale.ROOT, "%.2f", Math.max(0.0D, price));
                String itemName = displayNameFor(snapshot);

                String subtitle = colorize(subtitleTemplate)
                    .replace("{item}", itemName)
                    .replace("{price}", priceText)
                    .replace("{cmd}", cmd);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        return;
                    }
                    ItemStack latest = player.getInventory().getItemInMainHand();
                    if (latest == null || latest.getType() == Material.AIR || latest.getMaxStackSize() <= 1) {
                        lastShownHash.remove(playerId);
                        return;
                    }
                    try {
                        String latestHash = itemSerializer.sha256(itemSerializer.serializeToBase64(latest.clone().asOne()));
                        if (!hash.equals(latestHash)) {
                            return;
                        }
                    } catch (Exception e) {
                        return;
                    }

                    lastShownHash.put(playerId, hash);
                    player.sendTitle(title, subtitle, fadeIn, stay, fadeOut);
                });
            } catch (Exception e) {
                lastShownHash.remove(playerId);
            }
        });
    }

    private static String displayNameFor(ItemStack item) {
        if (item == null) return "未知物品";
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String dn = meta.getDisplayName();
            return dn == null || dn.isBlank() ? prettyMaterial(item.getType()) : dn;
        }
        return prettyMaterial(item.getType());
    }

    private static String prettyMaterial(Material material) {
        if (material == null) return "未知物品";
        String raw = material.name().toLowerCase(Locale.ROOT);
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isBlank()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        String s = sb.toString().trim();
        return s.isEmpty() ? material.name() : s;
    }

    private static String colorize(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}

