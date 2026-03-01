package com.ecobrain.plugin.gui;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.AMMCalculator;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场大盘 GUI（分页展示）。
 */
public class MarketViewGUI {
    public static final String TITLE_PREFIX = ChatColor.GOLD + "EcoBrain 市场大盘 - 第";
    public static final int BULK_BUTTON_SLOT = 45;
    public static final int PREV_PAGE_SLOT = 52;
    public static final int NEXT_PAGE_SLOT = 53;
    public static final int INFO_SLOT = 49;
    private static final int PAGE_SIZE = 45;

    private final AMMCalculator ammCalculator;
    private final ItemSerializer itemSerializer;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public MarketViewGUI(AMMCalculator ammCalculator, ItemSerializer itemSerializer) {
        this.ammCalculator = ammCalculator;
        this.itemSerializer = itemSerializer;
    }

    /**
     * 打开市场主菜单。
     * 菜单支持：直接左键购买 1 个，Shift+左键购买 1 组，右键(管理员)删除该物品档案。
     */
    public void open(Player player, List<ItemMarketRecord> allRecords, int page) {
        int maxPage = Math.max(1, (int) Math.ceil(allRecords.size() / (double) PAGE_SIZE));
        int safePage = Math.max(1, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(player, 54, TITLE_PREFIX + safePage + "页");
        Map<Integer, String> slotToHash = new HashMap<>();

        int start = (safePage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allRecords.size());
        for (int i = start; i < end; i++) {
            ItemMarketRecord record = allRecords.get(i);
            int slot = i - start;
            slotToHash.put(slot, record.getItemHash());
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
                lore.add(ChatColor.AQUA + "系统物理库存: " + record.getPhysicalStock());
                lore.add(ChatColor.DARK_GRAY + "(内部虚拟池: " + record.getCurrentInventory() + ")");
                lore.add(ChatColor.GREEN + "左键: 购买 1 个");
                lore.add(ChatColor.GREEN + "Shift+左键: 购买 1 组");
                lore.add(ChatColor.RED + "管理员右键: 删除该物品档案");
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(slot, item);
        }
        decorateBottomBar(inventory, safePage, maxPage);
        sessions.put(player.getUniqueId(), new Session(safePage, maxPage, slotToHash));
        player.openInventory(inventory);
    }

    public boolean isMarketTitle(String title) {
        return title != null && title.startsWith(TITLE_PREFIX);
    }

    public Session getSession(UUID playerId) {
        return sessions.get(playerId);
    }

    public void closeSession(UUID playerId) {
        sessions.remove(playerId);
    }

    private void decorateBottomBar(Inventory inventory, int page, int maxPage) {
        for (int i = 45; i <= 53; i++) {
            inventory.setItem(i, namedItem(Material.BLACK_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inventory.setItem(BULK_BUTTON_SLOT, namedItem(Material.HOPPER, ChatColor.GREEN + "打开批量出售"));
        inventory.setItem(INFO_SLOT, namedItem(Material.BOOK, ChatColor.GOLD + "第 " + page + " / " + maxPage + " 页"));

        if (page > 1) {
            inventory.setItem(PREV_PAGE_SLOT, namedItem(Material.ARROW, ChatColor.YELLOW + "上一页"));
        }
        if (page < maxPage) {
            inventory.setItem(NEXT_PAGE_SLOT, namedItem(Material.ARROW, ChatColor.YELLOW + "下一页"));
        }
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static class Session {
        private final int page;
        private final int maxPage;
        private final Map<Integer, String> slotToHash;

        public Session(int page, int maxPage, Map<Integer, String> slotToHash) {
            this.page = page;
            this.maxPage = maxPage;
            this.slotToHash = slotToHash;
        }

        public int page() {
            return page;
        }

        public int maxPage() {
            return maxPage;
        }

        public String hashAt(int slot) {
            return slotToHash.get(slot);
        }
    }
}
