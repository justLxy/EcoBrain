package com.ecobrain.plugin.gui;

import com.ecobrain.plugin.config.PluginSettings;
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
    private static final int PAGE_SIZE = 28;

    private final AMMCalculator ammCalculator;
    private final ItemSerializer itemSerializer;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private volatile List<String> loreTemplate;

    public MarketViewGUI(AMMCalculator ammCalculator, ItemSerializer itemSerializer, PluginSettings.Gui gui) {
        this.ammCalculator = ammCalculator;
        this.itemSerializer = itemSerializer;
        applySettings(gui);
    }

    /**
     * 热更新市场展示配置。
     */
    public final void applySettings(PluginSettings.Gui gui) {
        List<String> configured = gui.marketItemLoreTemplate();
        if (configured == null || configured.isEmpty()) {
            this.loreTemplate = List.of(
                "&7Hash: &f{hash_short}",
                "&7实时价格: &e{price}",
                "&7系统物理库存: &b{physical_stock}",
                "&8(内部虚拟池: {virtual_inventory})",
                "&a左键: 购买 1 个",
                "&aShift+左键: 购买 1 组",
                "&c管理员右键: 删除物品档案"
            );
            return;
        }
        this.loreTemplate = new ArrayList<>(configured);
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
        decorateBorder(inventory);
        List<Integer> contentSlots = getContentSlots();

        int start = (safePage - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, allRecords.size());
        for (int i = start; i < end; i++) {
            ItemMarketRecord record = allRecords.get(i);
            int slot = contentSlots.get(i - start);
            slotToHash.put(slot, record.getItemHash());
            ItemStack item = new ItemStack(org.bukkit.Material.PAPER);
            try {
                item = itemSerializer.deserializeFromBase64(record.getItemBase64());
            } catch (Exception ignored) {
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                List<String> lore = renderLore(record);
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            int visibleAmount = Math.max(1, Math.min(item.getMaxStackSize(), record.getPhysicalStock()));
            item.setAmount(visibleAmount);
            inventory.setItem(slot, item);
        }
        decorateBottomBar(inventory, safePage, maxPage);
        player.openInventory(inventory);
        sessions.put(player.getUniqueId(), new Session(safePage, maxPage, slotToHash));
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

    /**
     * 将 6x9 菜单外圈全部铺满黑玻璃，形成视觉边框。
     */
    private void decorateBorder(Inventory inventory) {
        ItemStack border = namedItem(Material.BLACK_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " ");
        for (int slot = 0; slot < 54; slot++) {
            if (isBorderSlot(slot)) {
                inventory.setItem(slot, border.clone());
            }
        }
    }

    private List<Integer> getContentSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < 54; slot++) {
            if (!isBorderSlot(slot)) {
                slots.add(slot);
            }
        }
        return slots;
    }

    private boolean isBorderSlot(int slot) {
        int row = slot / 9;
        int col = slot % 9;
        return row == 0 || row == 5 || col == 0 || col == 8;
    }

    private List<String> renderLore(ItemMarketRecord record) {
        List<String> lore = new ArrayList<>();
        String price = String.format("%.2f", ammCalculator.calculateCurrentPrice(record));
        String hashShort = record.getItemHash().substring(0, Math.min(12, record.getItemHash().length())) + "...";
        for (String line : loreTemplate) {
            String rendered = line
                .replace("{hash}", record.getItemHash())
                .replace("{hash_short}", hashShort)
                .replace("{price}", price)
                .replace("{physical_stock}", String.valueOf(record.getPhysicalStock()))
                .replace("{virtual_inventory}", String.valueOf(record.getCurrentInventory()));
            lore.add(ChatColor.translateAlternateColorCodes('&', rendered));
        }
        return lore;
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
