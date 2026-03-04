package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.gui.BulkSellGUI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家按 Q（丢弃键）时的保护：
 * - 第一次按 Q：取消丢弃并打开批量出售 GUI（避免随意丢垃圾）
 * - 短时间内再次按 Q：认为玩家刻意丢弃，放行丢弃且不再自动打开 GUI
 */
public class QDropBulkSellListener implements Listener {
    private final Plugin plugin;
    private final BulkSellGUI bulkSellGUI;
    private final ConcurrentHashMap<UUID, Long> lastDropMillis = new ConcurrentHashMap<>();

    public QDropBulkSellListener(Plugin plugin, BulkSellGUI bulkSellGUI) {
        this.plugin = plugin;
        this.bulkSellGUI = bulkSellGUI;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        // 避免在已经打开批量出售界面时再次干预（否则可能导致体验混乱）
        String title = player.getOpenInventory() == null ? null : player.getOpenInventory().getTitle();
        if (title != null && title.equals(bulkSellGUI.getTitle())) {
            return;
        }

        long now = System.currentTimeMillis();
        long windowMs = Math.max(200L, plugin.getConfig().getLong("trade.qdrop-open-bulk-sell-window-ms", 800L));

        UUID id = player.getUniqueId();
        Long last = lastDropMillis.get(id);
        if (last != null && now - last <= windowMs) {
            // 在窗口内的连续按 Q：放行丢弃，并更新窗口起点以便持续快速丢弃时不反复弹 GUI
            lastDropMillis.put(id, now);
            return;
        }

        lastDropMillis.put(id, now);
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            bulkSellGUI.open(player);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastDropMillis.remove(event.getPlayer().getUniqueId());
    }
}
