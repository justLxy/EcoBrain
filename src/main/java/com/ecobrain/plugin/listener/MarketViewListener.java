package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.EconomyService;
import com.ecobrain.plugin.service.MarketService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场主菜单交互监听：
 * - 左键购买 1 个
 * - Shift+左键购买 1 组
 * - 管理员右键删除物品档案
 * - 底栏按钮可跳转批量出售与翻页
 */
public class MarketViewListener implements Listener {
    private final Plugin plugin;
    private final MarketViewGUI marketViewGUI;
    private final BulkSellGUI bulkSellGUI;
    private final ItemMarketRepository repository;
    private final MarketService marketService;
    private final EconomyService economyService;
    private final ItemSerializer itemSerializer;
    private final ConcurrentHashMap<String, Boolean> actionInFlight = new ConcurrentHashMap<>();

    public MarketViewListener(Plugin plugin, MarketViewGUI marketViewGUI, BulkSellGUI bulkSellGUI,
                              ItemMarketRepository repository, MarketService marketService,
                              EconomyService economyService, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.marketViewGUI = marketViewGUI;
        this.bulkSellGUI = bulkSellGUI;
        this.repository = repository;
        this.marketService = marketService;
        this.economyService = economyService;
        this.itemSerializer = itemSerializer;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!marketViewGUI.isMarketTitle(event.getView().getTitle())) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 54) {
            event.setCancelled(true);
        } else {
            return;
        }

        MarketViewGUI.Session session = marketViewGUI.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (rawSlot == MarketViewGUI.BULK_BUTTON_SLOT) {
            bulkSellGUI.open(player);
            return;
        }
        if (rawSlot == MarketViewGUI.PREV_PAGE_SLOT && session.page() > 1) {
            openPageAsync(player, session.page() - 1);
            return;
        }
        if (rawSlot == MarketViewGUI.NEXT_PAGE_SLOT && session.page() < session.maxPage()) {
            openPageAsync(player, session.page() + 1);
            return;
        }

        String itemHash = session.hashAt(rawSlot);
        if (itemHash == null) {
            return;
        }

        if (event.isRightClick() && player.hasPermission("ecobrain.admin")) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                repository.deleteByHash(itemHash);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.GREEN + "已删除物品档案: " + itemHash);
                    openPageAsync(player, Math.min(session.page(), session.maxPage()));
                });
            });
            return;
        }

        if (!event.isLeftClick()) {
            return;
        }
        boolean shiftClick = event.isShiftClick();
        if (!economyService.isReady()) {
            player.sendMessage(ChatColor.RED + "Vault 经济未就绪，请联系管理员。");
            return;
        }
        if (!tryAcquireLock(player)) {
            player.sendMessage(ChatColor.RED + "你操作太快了，请稍后再试。");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<ItemMarketRecord> optionalRecord = repository.findByHash(itemHash);
                if (optionalRecord.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.RED + "该物品已下架或不存在。");
                        releaseLock(player);
                    });
                    return;
                }
                ItemMarketRecord record = optionalRecord.get();
                ItemStack template = itemSerializer.deserializeFromBase64(record.getItemBase64());
                int amount = shiftClick ? Math.max(1, template.getMaxStackSize()) : 1;

                if (record.getPhysicalStock() < amount) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.RED + "系统真实库存不足，无法出售！");
                        releaseLock(player);
                    });
                    return;
                }

                MarketService.TradeQuote quote = marketService.quoteBuy(record, amount);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (economyService.getBalance(player) < quote.totalPrice()) {
                            player.sendMessage(ChatColor.RED + "金币不足，需要 " + String.format("%.2f", quote.totalPrice()));
                            return;
                        }
                        if (!economyService.withdraw(player, quote.totalPrice())) {
                            player.sendMessage(ChatColor.RED + "扣款失败，交易取消。");
                            return;
                        }

                        int rest = amount;
                        while (rest > 0) {
                            ItemStack part = template.clone();
                            int stack = Math.min(part.getMaxStackSize(), rest);
                            part.setAmount(stack);
                            player.getInventory().addItem(part);
                            rest -= stack;
                        }
                        player.sendMessage(ChatColor.GREEN + "购买成功，花费 " + String.format("%.2f", quote.totalPrice()) + " 金币。");
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            marketService.settleBuy(itemHash, record, quote, amount);
                            List<ItemMarketRecord> refreshed = repository.findAll();
                            Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, refreshed, session.page()));
                        });
                    } finally {
                        releaseLock(player);
                    }
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "购买失败: " + e.getMessage());
                    releaseLock(player);
                });
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!marketViewGUI.isMarketTitle(event.getView().getTitle())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < 54) {
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
        if (!marketViewGUI.isMarketTitle(event.getView().getTitle())) {
            return;
        }
        marketViewGUI.closeSession(player.getUniqueId());
    }

    private void openPageAsync(Player player, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ItemMarketRecord> records = repository.findAll();
            Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, records, page));
        });
    }

    private boolean tryAcquireLock(Player player) {
        String key = player.getUniqueId().toString();
        return actionInFlight.putIfAbsent(key, true) == null;
    }

    private void releaseLock(Player player) {
        actionInFlight.remove(player.getUniqueId().toString());
    }
}
