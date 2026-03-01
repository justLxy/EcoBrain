package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.gui.LeaderboardGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;

public class LeaderboardListener implements Listener {
    private final Plugin plugin;
    private final LeaderboardGUI leaderboardGUI;
    private final MarketViewGUI marketViewGUI;
    private final ItemMarketRepository repository;

    public LeaderboardListener(Plugin plugin, LeaderboardGUI leaderboardGUI, MarketViewGUI marketViewGUI, ItemMarketRepository repository) {
        this.plugin = plugin;
        this.leaderboardGUI = leaderboardGUI;
        this.marketViewGUI = marketViewGUI;
        this.repository = repository;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!LeaderboardGUI.TITLE.equals(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true); // 榜单界面所有点击都取消，防止拿走物品
        
        int rawSlot = event.getRawSlot();
        if (rawSlot == LeaderboardGUI.BACK_BUTTON_SLOT) {
            // 返回市场大盘
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<ItemMarketRecord> records = repository.findAll();
                List<ItemMarketRecord> filtered = marketViewGUI.filterAndSort(records, player.getUniqueId());
                MarketViewGUI.Session session = marketViewGUI.getSession(player.getUniqueId());
                int page = session != null ? session.page() : 1;
                Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, filtered, page));
            });
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (LeaderboardGUI.TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }
}
