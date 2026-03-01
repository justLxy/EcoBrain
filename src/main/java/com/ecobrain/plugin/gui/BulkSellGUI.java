package com.ecobrain.plugin.gui;

import com.ecobrain.plugin.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 批量出售 GUI 工厂与会话管理。
 */
public class BulkSellGUI {
    public static final int SIZE = 54;
    public static final int SELL_BUTTON_SLOT = 45;
    public static final int CANCEL_BUTTON_SLOT = 53;
    public static final int INPUT_MAX_SLOT = 44;

    private volatile String title;
    private volatile Material sellButtonMaterial;
    private volatile String sellButtonName;
    private volatile java.util.List<String> sellButtonLore;
    private volatile Material cancelButtonMaterial;
    private volatile String cancelButtonName;
    private volatile java.util.List<String> cancelButtonLore;
    private volatile Material fillerMaterial;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public BulkSellGUI(PluginSettings.Gui gui) {
        applySettings(gui);
    }

    /**
     * 热更新 GUI 样式配置（材质、标题、名称、Lore）。
     */
    public final void applySettings(PluginSettings.Gui gui) {
        this.title = color(gui.bulkSellTitle());
        this.sellButtonMaterial = gui.sellButtonMaterial();
        this.sellButtonName = color(gui.sellButtonName());
        this.sellButtonLore = gui.sellButtonLore().stream().map(BulkSellGUI::color).collect(Collectors.toList());
        this.cancelButtonMaterial = gui.cancelButtonMaterial();
        this.cancelButtonName = color(gui.cancelButtonName());
        this.cancelButtonLore = gui.cancelButtonLore().stream().map(BulkSellGUI::color).collect(Collectors.toList());
        this.fillerMaterial = gui.fillerMaterial();
    }

    public void open(Player player) {
        Inventory inventory = Bukkit.createInventory(player, SIZE, title);
        decorateBottomBar(inventory);
        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new Session(UUID.randomUUID().toString()));
    }

    public Session getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public void closeSession(UUID playerId) {
        sessions.remove(playerId);
    }

    public String getTitle() {
        return title;
    }

    private void decorateBottomBar(Inventory inventory) {
        ItemStack sellButton = new ItemStack(sellButtonMaterial);
        ItemMeta sellMeta = sellButton.getItemMeta();
        if (sellMeta != null) {
            sellMeta.setDisplayName(sellButtonName);
            sellMeta.setLore(sellButtonLore);
            sellButton.setItemMeta(sellMeta);
        }

        ItemStack cancelButton = new ItemStack(cancelButtonMaterial);
        ItemMeta cancelMeta = cancelButton.getItemMeta();
        if (cancelMeta != null) {
            cancelMeta.setDisplayName(cancelButtonName);
            cancelMeta.setLore(cancelButtonLore);
            cancelButton.setItemMeta(cancelMeta);
        }

        for (int i = SELL_BUTTON_SLOT; i < SIZE; i++) {
            inventory.setItem(i, new ItemStack(fillerMaterial));
        }
        inventory.setItem(SELL_BUTTON_SLOT, sellButton);
        inventory.setItem(CANCEL_BUTTON_SLOT, cancelButton);
    }

    private static String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    public static class Session {
        private final String token;
        private volatile boolean settled;

        public Session(String token) {
            this.token = token;
        }

        public String getToken() {
            return token;
        }

        public boolean isSettled() {
            return settled;
        }

        public void setSettled(boolean settled) {
            this.settled = settled;
        }
    }
}
