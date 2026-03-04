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
    // 底栏布局：
    // 45/46: 筛选/排序（左下角与+1）
    // 49: 批量出售（原本书的位置）
    public static final int CATEGORY_BUTTON_SLOT = 45;
    public static final int SORT_BUTTON_SLOT = 46;
    public static final int BULK_BUTTON_SLOT = 49;
    public static final int REWARDS_BUTTON_SLOT = 8;
    public static final int PREV_PAGE_SLOT = 52;
    public static final int NEXT_PAGE_SLOT = 53;
    private static final int PAGE_SIZE = 28;

    private final AMMCalculator ammCalculator;
    private final ItemSerializer itemSerializer;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, FilterState> playerFilters = new ConcurrentHashMap<>();
    private volatile List<String> loreTemplate;

    public enum ItemCategory {
        ALL("全部物品", Material.NETHER_STAR),
        BLOCKS("方块类", Material.GRASS_BLOCK),
        RESOURCES("资源类", Material.DIAMOND),
        FOOD("食物类", Material.APPLE),
        OTHERS("其他类", Material.STICK);

        private final String displayName;
        private final Material icon;

        ItemCategory(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }
        
        public ItemCategory next() {
            return values()[(this.ordinal() + 1) % values().length];
        }
    }

    public enum SortMode {
        DEFAULT("默认排序(库存降序)", Material.COMPARATOR),
        STOCK_ASC("库存升序", Material.STONE_SLAB),
        STOCK_DESC("库存降序", Material.STONE),
        PRICE_DESC("价格降序", Material.GOLD_INGOT),
        PRICE_ASC("价格升序", Material.GOLD_NUGGET);

        private final String displayName;
        private final Material icon;

        SortMode(String displayName, Material icon) {
            this.displayName = displayName;
            this.icon = icon;
        }

        public String getDisplayName() { return displayName; }
        public Material getIcon() { return icon; }
        
        public SortMode next() {
            return values()[(this.ordinal() + 1) % values().length];
        }
    }

    public static ItemCategory getCategory(Material material) {
        String name = material.name();
        
        if (material.isEdible() || name.equals("MILK_BUCKET")) {
            return ItemCategory.FOOD;
        } else if (material.isBlock()) {
            return ItemCategory.BLOCKS;
        } else if (name.endsWith("_INGOT") || name.endsWith("_NUGGET") || name.startsWith("RAW_") || name.endsWith("_SHARD") || name.endsWith("_CRYSTAL") || name.equals("DIAMOND") || name.equals("EMERALD") || name.equals("COAL") || name.equals("CHARCOAL") || name.equals("REDSTONE") || name.equals("LAPIS_LAZULI") || name.equals("QUARTZ") || name.equals("NETHERITE_SCRAP") || name.equals("STICK") || name.equals("STRING") || name.equals("FEATHER") || name.equals("GUNPOWDER") || name.equals("LEATHER") || name.equals("BONE") || name.equals("PAPER") || name.equals("BOOK") || name.equals("SUGAR_CANE") || name.equals("SLIME_BALL") || name.equals("MAGMA_CREAM") || name.equals("BLAZE_ROD") || name.equals("ENDER_PEARL") || name.equals("ENDER_EYE") || name.equals("GHAST_TEAR") || name.equals("PHANTOM_MEMBRANE") || name.equals("SHULKER_SHELL") || name.equals("FLINT") || name.equals("CLAY_BALL") || name.equals("BRICK") || name.equals("NETHER_BRICK") || name.equals("RABBIT_HIDE") || name.equals("SCUTE") || name.equals("NAUTILUS_SHELL") || name.equals("HEART_OF_THE_SEA") || name.equals("NAME_TAG") || name.equals("SADDLE") || name.equals("HONEYCOMB") || name.endsWith("_DYE") || name.equals("INK_SAC") || name.equals("GLOW_INK_SAC") || name.equals("BONE_MEAL")) {
            return ItemCategory.RESOURCES;
        } else {
            return ItemCategory.OTHERS;
        }
    }

    public static class FilterState {
        public ItemCategory category = ItemCategory.ALL;
        public SortMode sortMode = SortMode.DEFAULT;
    }

    public FilterState getFilterState(UUID playerId) {
        return playerFilters.computeIfAbsent(playerId, k -> new FilterState());
    }

    public MarketViewGUI(AMMCalculator ammCalculator, ItemSerializer itemSerializer, PluginSettings.Gui gui, PluginSettings.AI ai) {
        this.ammCalculator = ammCalculator;
        this.itemSerializer = itemSerializer;
        applySettings(gui, ai);
    }

    /**
     * 热更新市场展示配置。
     */
    public final void applySettings(PluginSettings.Gui gui, PluginSettings.AI ai) {
        List<String> configured = gui.marketItemLoreTemplate();
        if (configured == null || configured.isEmpty()) {
            this.loreTemplate = List.of(
                "&7Hash: &f{hash_short}",
                "&7实时价格: &e{price}",
                "&7系统物理库存: &b{physical_stock}",
                "&8(内部虚拟池: {virtual_inventory})",
                "&a左键: 购买 1 个",
                "&aShift+左键: 购买 1 组",
                "&aShift+右键: 购买 10 组",
                "&a右键: 自定义数量购买",
                "&c管理员按Q: 删除物品档案"
            );
            return;
        }
        this.loreTemplate = new ArrayList<>(configured);
    }

    private String tierLabelFor(ItemMarketRecord item) {
        // EcoBrain 3.0 single-brain: no tier routing/labels.
        return "&f-";
    }

    /**
     * 打开市场主菜单。
     * 菜单支持：左键购买 1 个，Shift+左键购买 1 组，Shift+右键购买 10 组，右键(管理员)删除该物品档案。
     */
    public void open(Player player, List<ItemMarketRecord> allRecords, int page) {
        int maxPage = Math.max(1, (int) Math.ceil(allRecords.size() / (double) PAGE_SIZE));
        int safePage = Math.max(1, Math.min(page, maxPage));
        Inventory inventory = Bukkit.createInventory(player, 54, TITLE_PREFIX + safePage + "页");
        Map<Integer, String> slotToHash = new HashMap<>();
        decorateBorder(inventory, player);
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
                List<String> lore = renderLore(record, meta);
                meta.setLore(lore);
                item.setItemMeta(meta);
            }
            int visibleAmount = Math.max(1, Math.min(item.getMaxStackSize(), record.getPhysicalStock()));
            item.setAmount(visibleAmount);
            inventory.setItem(slot, item);
        }
        decorateBottomBar(inventory, safePage, maxPage, player.getUniqueId());
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

    /**
     * 根据玩家的筛选和排序状态处理记录。
     * 此方法应在异步线程调用以避免反序列化造成的卡顿。
     */
    public List<ItemMarketRecord> filterAndSort(List<ItemMarketRecord> records, UUID playerId) {
        FilterState state = getFilterState(playerId);
        
        List<ItemMarketRecord> result = new ArrayList<>();
        for (ItemMarketRecord record : records) {
            // 不在 GUI 中展示库存小于等于 0 的物品（它们已经断货）
            if (record.getPhysicalStock() <= 0) {
                continue;
            }

            boolean keep = true;
            if (state.category != ItemCategory.ALL) {
                try {
                    ItemStack item = itemSerializer.deserializeFromBase64(record.getItemBase64());
                    if (getCategory(item.getType()) != state.category) {
                        keep = false;
                    }
                } catch (Exception e) {
                    keep = false;
                }
            }
            if (keep) {
                result.add(record);
            }
        }

        if (state.sortMode == SortMode.PRICE_DESC || state.sortMode == SortMode.PRICE_ASC) {
            result.sort((a, b) -> {
                double priceA = ammCalculator.calculateCurrentPrice(a);
                double priceB = ammCalculator.calculateCurrentPrice(b);
                int cmp = Double.compare(priceA, priceB);
                return state.sortMode == SortMode.PRICE_ASC ? cmp : -cmp;
            });
        } else {
            result.sort((a, b) -> {
                int cmp = Integer.compare(a.getPhysicalStock(), b.getPhysicalStock());
                // DEFAULT 和 STOCK_DESC 都是降序（由多到少）
                return state.sortMode == SortMode.STOCK_ASC ? cmp : -cmp;
            });
        }

        return result;
    }

    private void decorateBottomBar(Inventory inventory, int page, int maxPage, UUID playerId) {
        for (int i = 45; i <= 53; i++) {
            inventory.setItem(i, namedItem(Material.BLACK_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " "));
        }
        inventory.setItem(BULK_BUTTON_SLOT, namedItem(Material.HOPPER, ChatColor.GREEN + "打开批量出售"));

        FilterState filterState = getFilterState(playerId);
        
        ItemStack categoryItem = namedItem(filterState.category.getIcon(), ChatColor.AQUA + "筛选分类: " + filterState.category.getDisplayName());
        ItemMeta catMeta = categoryItem.getItemMeta();
        if (catMeta != null) {
            catMeta.setLore(List.of(ChatColor.GRAY + "点击切换物品分类"));
            categoryItem.setItemMeta(catMeta);
        }
        inventory.setItem(CATEGORY_BUTTON_SLOT, categoryItem);

        ItemStack sortItem = namedItem(filterState.sortMode.getIcon(), ChatColor.AQUA + "排序方式: " + filterState.sortMode.getDisplayName());
        ItemMeta sortMeta = sortItem.getItemMeta();
        if (sortMeta != null) {
            sortMeta.setLore(List.of(ChatColor.GRAY + "点击切换排序方式"));
            sortItem.setItemMeta(sortMeta);
        }
        inventory.setItem(SORT_BUTTON_SLOT, sortItem);

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
    private void decorateBorder(Inventory inventory, Player player) {
        ItemStack border = namedItem(Material.BLACK_STAINED_GLASS_PANE, ChatColor.DARK_GRAY + " ");
        for (int slot = 0; slot < 54; slot++) {
            if (isBorderSlot(slot)) {
                inventory.setItem(slot, border.clone());
            }
        }
        
        // 正中心放置玩家头颅
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(player);
            meta.setDisplayName(ChatColor.GOLD + "欢迎来到系统市场，" + player.getName());
            List<String> headLore = new ArrayList<>();
            headLore.add(ChatColor.GRAY + "基础命令指南:");
            headLore.add(ChatColor.YELLOW + "/ecobrain sell" + ChatColor.WHITE + " - 出售主手物品");
            headLore.add(ChatColor.YELLOW + "/ecobrain sell all" + ChatColor.WHITE + " - 出售背包内所有同类物品");
            headLore.add(ChatColor.YELLOW + "/ecobrain buy <数量>" + ChatColor.WHITE + " - 按指定数量购买");
            headLore.add(ChatColor.GRAY + "或点击下方漏斗进行批量出售");
            headLore.add("");
            headLore.add(ChatColor.AQUA + "▶ 点击查看交易排行榜");
            meta.setLore(headLore);
            head.setItemMeta(meta);
        }
        inventory.setItem(4, head); // 0-8的第一行正中心是第4格

        // 右上角：奖励菜单入口
        ItemStack rewards = namedItem(Material.CHEST, ChatColor.LIGHT_PURPLE + "打开奖励菜单");
        ItemMeta rewardsMeta = rewards.getItemMeta();
        if (rewardsMeta != null) {
            rewardsMeta.setLore(List.of(
                ChatColor.GRAY + "点击查看并领取达成奖励",
                ChatColor.DARK_GRAY + "/ecobrain rewards"
            ));
            rewards.setItemMeta(rewardsMeta);
        }
        inventory.setItem(REWARDS_BUTTON_SLOT, rewards);
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
        // 第4格(第一行正中心)是玩家头颅，算作边框的一部分，不允许放商品
        if (slot == 4) return true;
        return row == 0 || row == 5 || col == 0 || col == 8;
    }

    private List<String> renderLore(ItemMarketRecord record, ItemMeta originalMeta) {
        List<String> lore = new ArrayList<>();
        
        // Preserve original item's lore if it exists
        if (originalMeta != null && originalMeta.hasLore()) {
            lore.addAll(originalMeta.getLore());
            lore.add(""); // Empty line to separate original lore from market info
        }
        
        String price = String.format("%.2f", ammCalculator.calculateCurrentPrice(record));
        String hashShort = record.getItemHash().substring(0, Math.min(12, record.getItemHash().length())) + "...";
        String tier = tierLabelFor(record);
        for (String line : loreTemplate) {
            String rendered = line
                .replace("{hash}", record.getItemHash())
                .replace("{hash_short}", hashShort)
                .replace("{price}", price)
                .replace("{tier}", tier)
                .replace("{physical_stock}", String.valueOf(record.getPhysicalStock()))
                .replace("{target_inventory}", String.valueOf(record.getTargetInventory()))
                .replace("{virtual_inventory}", String.valueOf(record.getCurrentInventory()));
            lore.add(ChatColor.translateAlternateColorCodes('&', rendered));
        }
        return lore;
    }

    /**
     * 将数据库记录渲染为市场展示用的 ItemStack（包含 lore 和数量显示）。
     * 用于“原地刷新”某个槽位，避免因重新排序导致玩家误点。
     */
    public ItemStack toMarketDisplayItem(ItemMarketRecord record) {
        ItemStack item = new ItemStack(org.bukkit.Material.PAPER);
        try {
            item = itemSerializer.deserializeFromBase64(record.getItemBase64());
        } catch (Exception ignored) {
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<String> lore = renderLore(record, meta);
            meta.setLore(lore);
            item.setItemMeta(meta);
        }
        int visibleAmount = Math.max(1, Math.min(item.getMaxStackSize(), Math.max(0, record.getPhysicalStock())));
        item.setAmount(visibleAmount);
        return item;
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

        public void clearSlot(int slot) {
            slotToHash.remove(slot);
        }
    }
}
