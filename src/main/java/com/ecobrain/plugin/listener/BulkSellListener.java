package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.EconomyService;
import com.ecobrain.plugin.service.MarketService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 批量出售 GUI 反刷监听器：
 * - 禁止底栏放入/拖拽物品
 * - ESC 关闭安全退回
 * - 确认出售后采用会话幂等与异步结算，防止重入复制漏洞
 */
public class BulkSellListener implements Listener {
    private final Plugin plugin;
    private final BulkSellGUI bulkSellGUI;
    private final ItemSerializer serializer;
    private final MarketService marketService;
    private final EconomyService economyService;

    public BulkSellListener(Plugin plugin, BulkSellGUI bulkSellGUI, ItemSerializer serializer,
                            MarketService marketService, EconomyService economyService) {
        this.plugin = plugin;
        this.bulkSellGUI = bulkSellGUI;
        this.serializer = serializer;
        this.marketService = marketService;
        this.economyService = economyService;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!bulkSellGUI.getTitle().equals(event.getView().getTitle())) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot >= BulkSellGUI.SELL_BUTTON_SLOT && rawSlot <= 53) {
            event.setCancelled(true);
        }

        if (rawSlot == BulkSellGUI.CANCEL_BUTTON_SLOT) {
            safeReturnAll(player, event.getInventory());
            player.closeInventory();
            return;
        }
        if (rawSlot == BulkSellGUI.SELL_BUTTON_SLOT) {
            event.setCancelled(true);
            confirmSell(player, event.getInventory());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!bulkSellGUI.getTitle().equals(event.getView().getTitle())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= BulkSellGUI.SELL_BUTTON_SLOT && slot <= 53) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!bulkSellGUI.getTitle().equals(event.getView().getTitle())) {
            return;
        }
        safeReturnAll(player, event.getInventory());
        bulkSellGUI.closeSession(player.getUniqueId());
    }

    /**
     * 核心批量出售逻辑：
     * 先在主线程抽离并清空输入区，之后异步计算所有品类的滑点收益并结算。
     */
    private void confirmSell(Player player, Inventory inventory) {
        BulkSellGUI.Session session = bulkSellGUI.getSession(player.getUniqueId());
        if (session == null || session.isSettled()) {
            player.sendMessage(ChatColor.RED + "本次会话已结算或无效。");
            return;
        }
        session.setSettled(true);

        List<ItemStack> snapshot = new ArrayList<>();
        for (int slot = 0; slot <= BulkSellGUI.INPUT_MAX_SLOT; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                snapshot.add(item.clone());
                inventory.setItem(slot, null);
            }
        }
        if (snapshot.isEmpty()) {
            session.setSettled(false);
            player.sendMessage(ChatColor.YELLOW + "没有可出售物品。");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Map<String, AggregatedItem> grouped = new HashMap<>();
                for (ItemStack item : snapshot) {
                    String base64 = serializer.serializeToBase64(item.asOne());
                    String hash = serializer.sha256(base64);
                    grouped.compute(hash, (k, old) -> {
                        if (old == null) {
                            return new AggregatedItem(base64, item.getAmount());
                        }
                        old.amount += item.getAmount();
                        return old;
                    });
                }

                double total = 0.0D;
                List<Runnable> settleActions = new ArrayList<>();
                for (Map.Entry<String, AggregatedItem> entry : grouped.entrySet()) {
                    String hash = entry.getKey();
                    AggregatedItem aggregatedItem = entry.getValue();
                    MarketService.IpoState ipoState = marketService
                        .ensureIpoForSellAsync(hash, aggregatedItem.base64, aggregatedItem.amount).join();
                    ItemMarketRecord record = ipoState.record();
                    MarketService.TradeQuote quote = marketService.quoteSell(record, aggregatedItem.amount);
                    total += quote.totalPrice();
                    settleActions.add(() -> marketService.settleSell(
                        hash, record, quote, aggregatedItem.amount, ipoState.createdNow()));
                }

                double finalTotal = total;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!economyService.deposit(player, finalTotal)) {
                        for (ItemStack item : snapshot) {
                            player.getInventory().addItem(item);
                        }
                        player.sendMessage(ChatColor.RED + "批量出售失败，物品已退回。");
                        session.setSettled(false);
                        return;
                    }
                    player.sendMessage(ChatColor.GREEN + "批量出售成功，共获得 " + String.format("%.2f", finalTotal) + " 金币。");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> settleActions.forEach(Runnable::run));
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (ItemStack item : snapshot) {
                        player.getInventory().addItem(item);
                    }
                    session.setSettled(false);
                    player.sendMessage(ChatColor.RED + "结算异常，物品已退回: " + e.getMessage());
                });
            }
        });
    }

    private void safeReturnAll(Player player, Inventory inventory) {
        for (int slot = 0; slot <= BulkSellGUI.INPUT_MAX_SLOT; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null && item.getType() != Material.AIR) {
                player.getInventory().addItem(item.clone());
                inventory.setItem(slot, null);
            }
        }
    }

    private static class AggregatedItem {
        private final String base64;
        private int amount;

        private AggregatedItem(String base64, int amount) {
            this.base64 = base64;
            this.amount = amount;
        }
    }
}
