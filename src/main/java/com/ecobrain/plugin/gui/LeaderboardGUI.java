package com.ecobrain.plugin.gui;

import com.ecobrain.plugin.model.PlayerStat;
import com.ecobrain.plugin.model.TradeType;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

public class LeaderboardGUI {
    public static final String TITLE = ChatColor.AQUA + "EcoBrain - 交易排行榜";
    public static final int BACK_BUTTON_SLOT = 49;

    public void open(Player player,
                     List<PlayerStat> topSellers,
                     List<PlayerStat> topBuyers,
                     double mySellMoney,
                     long mySellQty,
                     int mySellRank,
                     double myBuyMoney,
                     long myBuyQty,
                     int myBuyRank) {
        Inventory inventory = Bukkit.createInventory(player, 54, TITLE);
        
        // 装饰边框
        ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta borderMeta = border.getItemMeta();
        if (borderMeta != null) {
            borderMeta.setDisplayName(ChatColor.DARK_GRAY + " ");
            border.setItemMeta(borderMeta);
        }
        for (int i = 0; i < 54; i++) {
            int row = i / 9;
            int col = i % 9;
            if (row == 0 || row == 5 || col == 0 || col == 8) {
                inventory.setItem(i, border);
            }
        }

        // 标题说明
        inventory.setItem(4, namedItem(Material.EMERALD, ChatColor.GOLD + "交易排行榜"));
        
        // 出售排行榜 (玩家将物品出售给系统)
        int[] sellSlots = {10, 11, 12, 13, 14, 19, 20, 21, 22, 23};
        for (int i = 0; i < sellSlots.length; i++) {
            if (i < topSellers.size()) {
                PlayerStat stat = topSellers.get(i);
                inventory.setItem(sellSlots[i], createPlayerHead(stat.playerName(), i + 1, "出售总额", stat.totalMoney(), ChatColor.GREEN));
            } else {
                inventory.setItem(sellSlots[i], namedItem(Material.BARRIER, ChatColor.GRAY + "虚位以待"));
            }
        }
        
        // 购买排行榜 (玩家从系统购买物品)
        int[] buySlots = {28, 29, 30, 31, 32, 37, 38, 39, 40, 41};
        for (int i = 0; i < buySlots.length; i++) {
            if (i < topBuyers.size()) {
                PlayerStat stat = topBuyers.get(i);
                inventory.setItem(buySlots[i], createPlayerHead(stat.playerName(), i + 1, "消费总额", stat.totalMoney(), ChatColor.RED));
            } else {
                inventory.setItem(buySlots[i], namedItem(Material.BARRIER, ChatColor.GRAY + "虚位以待"));
            }
        }
        
        // 区域标识
        inventory.setItem(16, namedItem(Material.GOLD_INGOT, ChatColor.YELLOW + "↑ 爆肝出售榜 ↑"));
        inventory.setItem(25, namedItem(Material.GOLD_INGOT, ChatColor.YELLOW + "↑ 爆肝出售榜 ↑"));
        inventory.setItem(34, namedItem(Material.DIAMOND, ChatColor.AQUA + "↓ 消费神豪榜 ↓"));
        inventory.setItem(43, namedItem(Material.DIAMOND, ChatColor.AQUA + "↓ 消费神豪榜 ↓"));

        // 我的数据
        inventory.setItem(47, myStatItem(Material.EMERALD, ChatColor.GREEN + "我的出售", "出售总额", "出售数量", mySellMoney, mySellQty, mySellRank, ChatColor.GREEN));
        inventory.setItem(51, myStatItem(Material.REDSTONE, ChatColor.RED + "我的消费", "消费总额", "消费数量", myBuyMoney, myBuyQty, myBuyRank, ChatColor.RED));

        // 返回按钮
        ItemStack backButton = namedItem(Material.ARROW, ChatColor.YELLOW + "返回市场大盘");
        inventory.setItem(BACK_BUTTON_SLOT, backButton);

        player.openInventory(inventory);
    }

    private ItemStack createPlayerHead(String playerName, int rank, String typeName, double amount, ChatColor color) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerName));
            meta.setDisplayName(ChatColor.GOLD + "第 " + rank + " 名: " + ChatColor.WHITE + playerName);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + typeName + ": " + color + String.format("%.2f", amount) + " 金币");
            meta.setLore(lore);
            head.setItemMeta(meta);
        }
        return head;
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

    private ItemStack myStatItem(Material material, String title, String moneyName, String qtyName, double money, long qty, int rank, ChatColor moneyColor) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(title);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + moneyName + ": " + moneyColor + String.format("%.2f", money) + " 金币");
            lore.add(ChatColor.GRAY + qtyName + ": " + ChatColor.WHITE + qty);
            if (rank <= 0) {
                lore.add(ChatColor.GRAY + "榜位: " + ChatColor.DARK_GRAY + "暂无记录");
            } else {
                lore.add(ChatColor.GRAY + "榜位: " + ChatColor.GOLD + "第 " + rank + " 名");
            }
            meta.setLore(lore);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}
