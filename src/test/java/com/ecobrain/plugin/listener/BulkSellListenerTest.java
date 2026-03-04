package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.EconomyService;
import com.ecobrain.plugin.service.MarketService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;
import java.util.Set;

class BulkSellListenerTest {
    private BulkSellListener listener;
    private BulkSellGUI bulkSellGUI;

    @BeforeEach
    void setUp() {
        Plugin plugin = Mockito.mock(Plugin.class);
        PluginSettings.Gui gui = new PluginSettings.Gui(
            "&2EcoBrain 批量出售舱",
            Material.LIME_STAINED_GLASS_PANE, "&a确认出售", java.util.List.of("&7点击后将结算 0-44 格全部物品"),
            Material.RED_STAINED_GLASS_PANE, "&c取消并退回", java.util.List.of("&7安全退回所有待售物品"),
            Material.BLACK_STAINED_GLASS_PANE,
            java.util.List.of("&7实时价格: &e{price}")
        );
        bulkSellGUI = new BulkSellGUI(gui);
        MarketService marketService = Mockito.mock(MarketService.class);
        EconomyService economyService = Mockito.mock(EconomyService.class);
        com.ecobrain.plugin.persistence.ItemMarketRepository repository = Mockito.mock(com.ecobrain.plugin.persistence.ItemMarketRepository.class);
        ItemSerializer serializer = new ItemSerializer();
        listener = new BulkSellListener(plugin, bulkSellGUI, serializer, marketService, economyService, repository);
    }

    @Test
    void shouldBlockClickIntoBottomBarSlots() {
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        InventoryView view = Mockito.mock(InventoryView.class);
        Player player = Mockito.mock(Player.class);

        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(view.getTitle()).thenReturn(bulkSellGUI.getTitle());
        Mockito.when(event.getRawSlot()).thenReturn(46);

        listener.onClick(event);

        Mockito.verify(event, Mockito.atLeastOnce()).setCancelled(true);
    }

    @Test
    void shouldReturnItemsWhenClickCancelButton() {
        InventoryClickEvent event = Mockito.mock(InventoryClickEvent.class);
        InventoryView view = Mockito.mock(InventoryView.class);
        Inventory topInventory = Mockito.mock(Inventory.class);
        Player player = Mockito.mock(Player.class);
        PlayerInventory playerInventory = Mockito.mock(PlayerInventory.class);
        ItemStack stack = new ItemStack(Material.DIAMOND, 8);

        Mockito.when(event.getWhoClicked()).thenReturn(player);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(view.getTitle()).thenReturn(bulkSellGUI.getTitle());
        Mockito.when(event.getRawSlot()).thenReturn(BulkSellGUI.CANCEL_BUTTON_SLOT);
        Mockito.when(event.getInventory()).thenReturn(topInventory);
        Mockito.when(player.getInventory()).thenReturn(playerInventory);
        Mockito.when(topInventory.getItem(0)).thenReturn(stack);

        listener.onClick(event);

        ArgumentCaptor<ItemStack[]> clickCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        Mockito.verify(playerInventory).addItem(clickCaptor.capture());
        ItemStack returnedByCancel = clickCaptor.getValue()[0];
        org.junit.jupiter.api.Assertions.assertEquals(Material.DIAMOND, returnedByCancel.getType());
        org.junit.jupiter.api.Assertions.assertEquals(8, returnedByCancel.getAmount());
        Mockito.verify(topInventory).setItem(0, null);
        Mockito.verify(player).closeInventory();
    }

    @Test
    void shouldBlockDragToBottomBarSlots() {
        InventoryDragEvent event = Mockito.mock(InventoryDragEvent.class);
        InventoryView view = Mockito.mock(InventoryView.class);

        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(view.getTitle()).thenReturn(bulkSellGUI.getTitle());
        Mockito.when(event.getRawSlots()).thenReturn(Set.of(BulkSellGUI.SELL_BUTTON_SLOT));

        listener.onDrag(event);

        Mockito.verify(event).setCancelled(true);
    }

    @Test
    void shouldReturnItemsWhenInventoryClosedByEsc() {
        InventoryCloseEvent event = Mockito.mock(InventoryCloseEvent.class);
        InventoryView view = Mockito.mock(InventoryView.class);
        Inventory topInventory = Mockito.mock(Inventory.class);
        Player player = Mockito.mock(Player.class);
        PlayerInventory playerInventory = Mockito.mock(PlayerInventory.class);
        ItemStack stack = new ItemStack(Material.IRON_INGOT, 12);

        Mockito.when(event.getPlayer()).thenReturn(player);
        Mockito.when(event.getView()).thenReturn(view);
        Mockito.when(view.getTitle()).thenReturn(bulkSellGUI.getTitle());
        Mockito.when(event.getInventory()).thenReturn(topInventory);
        Mockito.when(topInventory.getItem(0)).thenReturn(stack);
        Mockito.when(player.getInventory()).thenReturn(playerInventory);
        Mockito.when(player.getUniqueId()).thenReturn(UUID.randomUUID());

        listener.onClose(event);

        ArgumentCaptor<ItemStack[]> closeCaptor = ArgumentCaptor.forClass(ItemStack[].class);
        Mockito.verify(playerInventory).addItem(closeCaptor.capture());
        ItemStack returnedByClose = closeCaptor.getValue()[0];
        org.junit.jupiter.api.Assertions.assertEquals(Material.IRON_INGOT, returnedByClose.getType());
        org.junit.jupiter.api.Assertions.assertEquals(12, returnedByClose.getAmount());
        Mockito.verify(topInventory).setItem(0, null);
    }
}
