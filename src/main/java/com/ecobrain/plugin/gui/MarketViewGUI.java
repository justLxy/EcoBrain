package com.ecobrain.plugin.gui;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.AMMCalculator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 市场大盘 GUI（分页展示）。
 */
public class MarketViewGUI {
    public static final String TITLE_PREFIX = ChatColor.GOLD + "EcoBrain 市场大盘 - 第";
    private static final int PAGE_SIZE = 45;

    private final AMMCalculator ammCalculator;
    private final ItemSerializer itemSerializer;

    public MarketViewGUI(AMMCalculator ammCalculator, ItemSerializer itemSerializer) {
        this.ammCalculator = ammCalculator;
        this.itemSerializer = itemSerializer;
    }

    public void open(Player player, List<ItemMarketRecord> allRecords, int page) {
        int maxPage = Math.max(1, (int) Math.ceil(allRecords.size() / (double) PAGE_SIZE));
        int safePage = Math.max(1, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(player, 54, TITLE_PREFIX + safePage + "页");

        int start = (safePage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allRecords.size());
        for (int i = start; i < end; i++) {
            ItemMarketRecord record = allRecords.get(i);
            int slot = i - start;
            ItemStack item = new ItemStack(org.bukkit.Material.PAPER);
            try {
                item = itemSerializer.deserializeFromBase64(record.getItemBase64());
            } catch (Exception ignored) {
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.GRAY + "Hash: " + record.getItemHash().substring(0, 12) + "...");
                lore.add(ChatColor.YELLOW + "实时价格: " + String.format("%.2f", ammCalculator.calculateCurrentPrice(record)));
                lore.add(ChatColor.AQUA + "系统库存: " + record.getCurrentInventory());
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
        }
        player.openInventory(inventory);
    }
}
