package com.ecobrain.plugin.listener;

import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.rewards.RewardsGUI;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.EconomyService;
import com.ecobrain.plugin.service.MarketService;
import com.ecobrain.plugin.gui.LeaderboardGUI;
import com.ecobrain.plugin.model.PlayerStat;
import com.ecobrain.plugin.model.TradeType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 市场主菜单交互监听：
 * - 左键购买 1 个
 * - Shift+左键购买 1 组
 * - Shift+右键购买 10 组
 * - 右键输入自定义购买数量
 * - 管理员按 Q 删除物品档案
 * - 底栏按钮可跳转批量出售与翻页
 */
public class MarketViewListener implements Listener {
    private final Plugin plugin;
    private final MarketViewGUI marketViewGUI;
    private final BulkSellGUI bulkSellGUI;
    private final LeaderboardGUI leaderboardGUI;
    private final RewardsGUI rewardsGUI;
    private final ItemMarketRepository repository;
    private final MarketService marketService;
    private final EconomyService economyService;
    private final ItemSerializer itemSerializer;
    private final ConcurrentHashMap<String, Boolean> actionInFlight = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID, Long> clickCooldown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID, PendingBuyInput> pendingBuyInput = new ConcurrentHashMap<>();

    private enum BuyMode {
        ONE,
        ONE_STACK,
        TEN_STACKS,
        EXACT
    }

    private static class PendingBuyInput {
        private final String itemHash;
        private final int page;
        private final long createdAtMillis;

        private PendingBuyInput(String itemHash, int page, long createdAtMillis) {
            this.itemHash = itemHash;
            this.page = page;
            this.createdAtMillis = createdAtMillis;
        }
    }

    public MarketViewListener(Plugin plugin, MarketViewGUI marketViewGUI, BulkSellGUI bulkSellGUI,
                              LeaderboardGUI leaderboardGUI, RewardsGUI rewardsGUI, ItemMarketRepository repository, MarketService marketService,
                              EconomyService economyService, ItemSerializer itemSerializer) {
        this.plugin = plugin;
        this.marketViewGUI = marketViewGUI;
        this.bulkSellGUI = bulkSellGUI;
        this.leaderboardGUI = leaderboardGUI;
        this.rewardsGUI = rewardsGUI;
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
        
        // Simple anti-spam for all clicks in this GUI
        long now = System.currentTimeMillis();
        Long lastClick = clickCooldown.get(player.getUniqueId());
        if (lastClick != null && now - lastClick < 200) {
            event.setCancelled(true);
            return;
        }
        clickCooldown.put(player.getUniqueId(), now);
        
        int rawSlot = event.getRawSlot();
        if (event.getAction() == org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY ||
            event.getAction() == org.bukkit.event.inventory.InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
        }
        if (rawSlot < 54) {
            event.setCancelled(true);
        } else {
            return;
        }

        MarketViewGUI.Session session = marketViewGUI.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }

        if (rawSlot == 4) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<PlayerStat> topSellers = repository.getTopPlayers(TradeType.SELL, 10);
                List<PlayerStat> topBuyers = repository.getTopPlayers(TradeType.BUY, 10);
                double mySellMoney = repository.getPlayerTotalMoney(player.getUniqueId(), TradeType.SELL);
                long mySellQty = repository.getPlayerTotalQuantity(player.getUniqueId(), TradeType.SELL);
                int mySellRank = repository.getPlayerRank(player.getUniqueId(), TradeType.SELL);
                double myBuyMoney = repository.getPlayerTotalMoney(player.getUniqueId(), TradeType.BUY);
                long myBuyQty = repository.getPlayerTotalQuantity(player.getUniqueId(), TradeType.BUY);
                int myBuyRank = repository.getPlayerRank(player.getUniqueId(), TradeType.BUY);
                Bukkit.getScheduler().runTask(plugin, () -> leaderboardGUI.open(
                    player,
                    topSellers,
                    topBuyers,
                    mySellMoney,
                    mySellQty,
                    mySellRank,
                    myBuyMoney,
                    myBuyQty,
                    myBuyRank
                ));
            });
            return;
        }

        if (rawSlot == MarketViewGUI.BULK_BUTTON_SLOT) {
            bulkSellGUI.open(player);
            return;
        }
        if (rawSlot == MarketViewGUI.REWARDS_BUTTON_SLOT) {
            if (!player.hasPermission("ecobrain.rewards")) {
                player.sendMessage(ChatColor.RED + "你没有权限打开奖励菜单。");
                return;
            }
            rewardsGUI.open(player);
            return;
        }
        if (rawSlot == MarketViewGUI.CATEGORY_BUTTON_SLOT) {
            MarketViewGUI.FilterState state = marketViewGUI.getFilterState(player.getUniqueId());
            state.category = state.category.next();
            openPageAsync(player, 1); // Reset to page 1 on filter change
            return;
        }
        if (rawSlot == MarketViewGUI.SORT_BUTTON_SLOT) {
            MarketViewGUI.FilterState state = marketViewGUI.getFilterState(player.getUniqueId());
            state.sortMode = state.sortMode.next();
            openPageAsync(player, 1); // Reset to page 1 on sort change
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

        // 管理员：按 Q 删除档案（避免与“右键自定义购买”冲突）
        if (isAdminDeleteClick(event) && player.hasPermission("ecobrain.admin")) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                repository.deleteByHash(itemHash);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.GREEN + "已删除物品档案: " + itemHash);
                    openPageAsync(player, Math.min(session.page(), session.maxPage()));
                });
            });
            return;
        }

        boolean buyOne = event.isLeftClick() && !event.isShiftClick();
        boolean buyOneStack = event.isLeftClick() && event.isShiftClick();
        boolean buyTenStacks = event.isRightClick() && event.isShiftClick();
        boolean customBuy = event.isRightClick() && !event.isShiftClick();
        if (!buyOne && !buyOneStack && !buyTenStacks && !customBuy) {
            return;
        }
        if (!economyService.isReady()) {
            player.sendMessage(ChatColor.RED + "Vault 经济未就绪，请联系管理员。");
            return;
        }
        if (customBuy) {
            promptCustomBuy(player, itemHash, session.page());
            return;
        }

        BuyMode mode = buyTenStacks ? BuyMode.TEN_STACKS : (buyOneStack ? BuyMode.ONE_STACK : BuyMode.ONE);
        startBuyFlow(player, itemHash, 1, mode, session.page(), rawSlot);
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingBuyInput pending = pendingBuyInput.get(player.getUniqueId());
        if (pending == null) {
            return;
        }
        event.setCancelled(true);
        String msg = event.getMessage() == null ? "" : event.getMessage().trim();
        if (msg.equalsIgnoreCase("cancel") || msg.equalsIgnoreCase("c") || msg.equalsIgnoreCase("取消")) {
            pendingBuyInput.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "已取消自定义购买。");
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(msg);
        } catch (NumberFormatException e) {
            player.sendMessage(ChatColor.YELLOW + "请输入一个正整数数量，或输入 cancel 取消。");
            return;
        }
        if (amount <= 0) {
            player.sendMessage(ChatColor.YELLOW + "数量必须是正整数，或输入 cancel 取消。");
            return;
        }
        // prevent stale pending forever
        if (System.currentTimeMillis() - pending.createdAtMillis > 60_000L) {
            pendingBuyInput.remove(player.getUniqueId());
            player.sendMessage(ChatColor.YELLOW + "输入已超时，请重新右键选择物品。");
            return;
        }
        pendingBuyInput.remove(player.getUniqueId());
        int finalAmount = Math.min(10000, amount);
        Bukkit.getScheduler().runTask(plugin, () -> startBuyFlow(player, pending.itemHash, finalAmount, BuyMode.EXACT, pending.page, -1));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        pendingBuyInput.remove(event.getPlayer().getUniqueId());
        clickCooldown.remove(event.getPlayer().getUniqueId());
        actionInFlight.remove(event.getPlayer().getUniqueId().toString());
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
        // 刷新/翻页会触发“旧菜单关闭 + 新菜单打开”，此时不应清理会话。
        if (marketViewGUI.isMarketTitle(player.getOpenInventory().getTitle())) {
            return;
        }
        marketViewGUI.closeSession(player.getUniqueId());
        clickCooldown.remove(player.getUniqueId());
    }

    private void openPageAsync(Player player, int page) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<ItemMarketRecord> records = repository.findAll();
            List<ItemMarketRecord> filtered = marketViewGUI.filterAndSort(records, player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, filtered, page));
        });
    }

    private boolean tryAcquireLock(Player player) {
        String key = player.getUniqueId().toString();
        return actionInFlight.putIfAbsent(key, true) == null;
    }

    private void releaseLock(Player player) {
        actionInFlight.remove(player.getUniqueId().toString());
    }

    private boolean isAdminDeleteClick(InventoryClickEvent event) {
        ClickType click = event.getClick();
        return click == ClickType.DROP || click == ClickType.CONTROL_DROP;
    }

    private void promptCustomBuy(Player player, String itemHash, int page) {
        // close the GUI to avoid confusion when typing
        player.closeInventory();
        pendingBuyInput.put(player.getUniqueId(), new PendingBuyInput(itemHash, page, System.currentTimeMillis()));
        player.sendMessage(ChatColor.AQUA + "请输入你想购买的数量（输入 cancel 取消）。正在计算背包可容纳上限...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Optional<ItemMarketRecord> optional = repository.findByHash(itemHash);
                if (optional.isEmpty()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "该物品已下架或不存在。"));
                    pendingBuyInput.remove(player.getUniqueId());
                    return;
                }
                ItemMarketRecord record = optional.get();
                ItemStack template = itemSerializer.deserializeFromBase64(record.getItemBase64());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    int max = computeMaxAddable(player.getInventory(), template);
                    if (max <= 0) {
                        player.sendMessage(ChatColor.RED + "你的背包没有足够空间放入该物品。");
                        pendingBuyInput.remove(player.getUniqueId());
                        return;
                    }
                    player.sendMessage(ChatColor.GREEN + "当前背包最多可购买: " + max + " 个。");
                    player.sendMessage(ChatColor.YELLOW + "请直接在聊天输入数量（1~" + max + "），或输入 cancel 取消。");
                });
            } catch (Exception e) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "计算失败: " + e.getMessage()));
                pendingBuyInput.remove(player.getUniqueId());
            }
        });
    }

    private int computeMaxAddable(PlayerInventory inventory, ItemStack template) {
        int maxStack = Math.max(1, template.getMaxStackSize());
        int free = 0;
        for (ItemStack slot : inventory.getStorageContents()) {
            if (slot == null || slot.getType().isAir()) {
                free += maxStack;
                continue;
            }
            if (slot.isSimilar(template)) {
                free += Math.max(0, maxStack - slot.getAmount());
            }
        }
        ItemStack offhand = inventory.getItemInOffHand();
        if (offhand == null || offhand.getType().isAir()) {
            free += maxStack;
        } else if (offhand.isSimilar(template)) {
            free += Math.max(0, maxStack - offhand.getAmount());
        }
        return Math.max(0, free);
    }

    private void startBuyFlow(Player player,
                              String itemHash,
                              int requestedAmount,
                              BuyMode mode,
                              int reopenPage,
                              int clickedSlot) {
        if (!tryAcquireLock(player)) {
            player.sendMessage(ChatColor.RED + "你操作太快了，请稍后再试。");
            return;
        }
        ItemStack[] storageSnapshot = cloneContents(player.getInventory().getStorageContents());
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack offhandSnapshot = offhand == null ? null : offhand.clone();

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
                int stackSize = Math.max(1, template.getMaxStackSize());
                int amount = requestedAmount;
                if (mode == BuyMode.TEN_STACKS) {
                    amount = Math.max(1, stackSize * 10);
                } else if (mode == BuyMode.ONE_STACK) {
                    amount = stackSize;
                } else if (mode == BuyMode.ONE) {
                    amount = 1;
                }

                int maxByCapacitySnapshot = 0;
                try {
                    maxByCapacitySnapshot = computeMaxAddable(storageSnapshot, offhandSnapshot, template);
                } catch (Exception ignored) {
                }

                int finalAmount = amount;
                if (maxByCapacitySnapshot > 0) {
                    finalAmount = Math.min(finalAmount, maxByCapacitySnapshot);
                }
                finalAmount = Math.min(10000, Math.max(1, finalAmount));

                int currentPhysical = record.getPhysicalStock();
                int criticalLimit = plugin.getConfig().getInt("circuit-breaker.critical-inventory", 2);

                if (currentPhysical <= criticalLimit) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.RED + "系统展柜保护机制：该物品库存已达红线，暂停出售！");
                        releaseLock(player);
                    });
                    return;
                }
                if (currentPhysical - finalAmount < criticalLimit) {
                    int maxCanBuy = currentPhysical - criticalLimit;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.sendMessage(ChatColor.RED + "购买失败！系统最多只能再向您出售 " + maxCanBuy + " 个该物品。");
                        releaseLock(player);
                    });
                    return;
                }

                MarketService.TradeQuote quote = marketService.quoteBuy(record, finalAmount);
                int finalAmount1 = finalAmount;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        int currentMax = computeMaxAddable(player.getInventory(), template);
                        if (currentMax < finalAmount1) {
                            player.sendMessage(ChatColor.RED + "背包空间不足，交易已取消。（可放: " + currentMax + " 个）");
                            return;
                        }
                        if (economyService.getBalance(player) < quote.totalPrice()) {
                            player.sendMessage(ChatColor.RED + "金币不足，需要 " + String.format("%.2f", quote.totalPrice()));
                            return;
                        }
                        if (!economyService.withdraw(player, quote.totalPrice())) {
                            player.sendMessage(ChatColor.RED + "扣款失败，交易取消。");
                            return;
                        }

                        int rest = finalAmount1;
                        while (rest > 0) {
                            ItemStack part = template.clone();
                            int stack = Math.min(part.getMaxStackSize(), rest);
                            part.setAmount(stack);
                            java.util.Map<Integer, ItemStack> leftover = player.getInventory().addItem(part);
                            if (!leftover.isEmpty()) {
                                // theoretically should not happen due to capacity check; keep safe fallback.
                                for (ItemStack drop : leftover.values()) {
                                    player.getWorld().dropItem(player.getLocation(), drop);
                                }
                            }
                            rest -= stack;
                        }
                        player.sendMessage(ChatColor.GREEN + "购买成功，花费 " + String.format("%.2f", quote.totalPrice()) + " 金币。");
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            marketService.settleBuy(player, itemHash, record, quote, finalAmount1);
                            // 关键：不要“重开并重新排序”，否则库存/价格变化会让物品换位置，玩家连续点击旧位置可能误买。
                            // - 若购买来自 GUI 点击：原地刷新被点击的槽位
                            // - 若购买来自聊天输入（GUI 已关闭）：照常重开页面（此时玩家不会在旧位置连点）
                            if (clickedSlot >= 0) {
                                Optional<ItemMarketRecord> updatedOpt = repository.findByHash(itemHash);
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    if (!marketViewGUI.isMarketTitle(player.getOpenInventory().getTitle())) {
                                        return;
                                    }
                                    MarketViewGUI.Session session = marketViewGUI.getSession(player.getUniqueId());
                                    if (session == null) {
                                        return;
                                    }
                                    if (updatedOpt.isEmpty() || updatedOpt.get().getPhysicalStock() <= 0) {
                                        player.getOpenInventory().getTopInventory().setItem(clickedSlot, null);
                                        session.clearSlot(clickedSlot);
                                        return;
                                    }
                                    ItemStack display = marketViewGUI.toMarketDisplayItem(updatedOpt.get());
                                    player.getOpenInventory().getTopInventory().setItem(clickedSlot, display);
                                });
                            } else {
                                List<ItemMarketRecord> refreshed = repository.findAll();
                                List<ItemMarketRecord> filtered = marketViewGUI.filterAndSort(refreshed, player.getUniqueId());
                                Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, filtered, reopenPage));
                            }
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

    private ItemStack[] cloneContents(ItemStack[] source) {
        ItemStack[] copy = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] == null ? null : source[i].clone();
        }
        return copy;
    }

    private int computeMaxAddable(ItemStack[] storageContents, ItemStack offhand, ItemStack template) {
        int maxStack = Math.max(1, template.getMaxStackSize());
        int free = 0;
        for (ItemStack slot : storageContents) {
            if (slot == null || slot.getType().isAir()) {
                free += maxStack;
                continue;
            }
            if (slot.isSimilar(template)) {
                free += Math.max(0, maxStack - slot.getAmount());
            }
        }
        if (offhand == null || offhand.getType().isAir()) {
            free += maxStack;
        } else if (offhand.isSimilar(template)) {
            free += Math.max(0, maxStack - offhand.getAmount());
        }
        return Math.max(0, free);
    }
}
