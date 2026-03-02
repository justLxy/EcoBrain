package com.ecobrain.plugin.rewards;

import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RewardsGUI {
    public static final String TITLE_PREFIX = ChatColor.AQUA + "EcoBrain - 奖励";

    public record Session(int size, String title, Map<Integer, String> rewardIdAtSlot) {}

    private final Plugin plugin;
    private final RewardsManager rewardsManager;
    private final ItemMarketRepository marketRepository;
    private final RewardClaimRepository claimRepository;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();

    public RewardsGUI(Plugin plugin, RewardsManager rewardsManager, ItemMarketRepository marketRepository, RewardClaimRepository claimRepository) {
        this.plugin = plugin;
        this.rewardsManager = rewardsManager;
        this.marketRepository = marketRepository;
        this.claimRepository = claimRepository;
    }

    public boolean isRewardsTitle(String title) {
        return title != null && title.startsWith(TITLE_PREFIX);
    }

    public Session getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public void closeSession(UUID uuid) {
        sessions.remove(uuid);
    }

    public void open(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            RewardsConfig cfg = rewardsManager.config();
            UUID uuid = player.getUniqueId();

            double sellMoney = marketRepository.getPlayerTotalMoney(uuid, TradeType.SELL);
            long sellQty = marketRepository.getPlayerTotalQuantity(uuid, TradeType.SELL);
            int sellRank = marketRepository.getPlayerRank(uuid, TradeType.SELL);
            double buyMoney = marketRepository.getPlayerTotalMoney(uuid, TradeType.BUY);
            long buyQty = marketRepository.getPlayerTotalQuantity(uuid, TradeType.BUY);
            int buyRank = marketRepository.getPlayerRank(uuid, TradeType.BUY);

            Set<String> claimed = claimRepository.getClaimedRewardIds(uuid);

            String title = color(cfg.gui().title());
            Inventory inv = Bukkit.createInventory(player, cfg.gui().size(), title);
            Map<Integer, String> slotMap = new HashMap<>();

            // border
            ItemStack border = namedItem(cfg.gui().borderMaterial(), color(cfg.gui().borderName()));
            for (int i = 0; i < cfg.gui().size(); i++) {
                int row = i / 9;
                int col = i % 9;
                if (row == 0 || row == (cfg.gui().size() / 9) - 1 || col == 0 || col == 8) {
                    inv.setItem(i, border);
                }
            }

            // back
            inv.setItem(cfg.gui().backSlot(), namedItem(cfg.gui().backMaterial(), color(cfg.gui().backName())));

            // rewards grid (skip border slots)
            int slot = 10;
            for (RewardDefinition def : cfg.rewards()) {
                while (slot < cfg.gui().size() && isBorderSlot(slot, cfg.gui().size())) {
                    slot++;
                }
                if (slot >= cfg.gui().size()) {
                    break;
                }

                Progress p = progressFor(def.type(), def.target(), sellMoney, sellQty, sellRank, buyMoney, buyQty, buyRank);
                boolean unlocked = p.progress() >= p.target();
                boolean isClaimed = claimed.contains(def.id());

                String statusText = isClaimed
                    ? ChatColor.GREEN + "已领取"
                    : (unlocked ? ChatColor.YELLOW + "可领取" : ChatColor.RED + "未达成");

                ItemStack item = new ItemStack(def.displayMaterial() == null ? Material.PAPER : def.displayMaterial());
                ItemMeta meta = item.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(color(replaceVars(def.displayName(), p, statusText)));
                    List<String> lore = new ArrayList<>();
                    for (String line : def.displayLore()) {
                        lore.add(color(replaceVars(line, p, statusText)));
                    }
                    if (lore.isEmpty()) {
                        lore.add(ChatColor.GRAY + "进度: " + ChatColor.WHITE + formatProgress(p) + ChatColor.GRAY + "/" + ChatColor.WHITE + formatTarget(p));
                        lore.add(ChatColor.GRAY + "榜位: " + ChatColor.GOLD + (p.rank() <= 0 ? "-" : p.rank()));
                        lore.add(ChatColor.GRAY + "状态: " + statusText);
                    }
                    meta.setLore(lore);
                    meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
                    if (unlocked && !isClaimed) {
                        meta.addEnchant(org.bukkit.enchantments.Enchantment.LUCK, 1, true);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    }
                    item.setItemMeta(meta);
                }

                inv.setItem(slot, item);
                slotMap.put(slot, def.id());
                slot++;
            }

            Session session = new Session(cfg.gui().size(), title, Map.copyOf(slotMap));
            sessions.put(uuid, session);

            Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(inv));
        });
    }

    public record Progress(RewardType type, double target, double progress, int rank) {}

    private Progress progressFor(RewardType type, double target,
                                 double sellMoney, long sellQty, int sellRank,
                                 double buyMoney, long buyQty, int buyRank) {
        return switch (type) {
            case SELL_MONEY -> new Progress(type, target, sellMoney, sellRank);
            case SELL_QTY -> new Progress(type, target, (double) sellQty, sellRank);
            case BUY_MONEY -> new Progress(type, target, buyMoney, buyRank);
            case BUY_QTY -> new Progress(type, target, (double) buyQty, buyRank);
        };
    }

    private boolean isBorderSlot(int slot, int size) {
        int row = slot / 9;
        int col = slot % 9;
        int maxRow = (size / 9) - 1;
        return row == 0 || row == maxRow || col == 0 || col == 8;
    }

    private String replaceVars(String text, Progress p, String statusText) {
        if (text == null) {
            return "";
        }
        return text
            .replace("{target}", formatTarget(p))
            .replace("{progress}", formatProgress(p))
            .replace("{rank}", p.rank() <= 0 ? "-" : Integer.toString(p.rank()))
            .replace("{status}", statusText);
    }

    private String formatTarget(Progress p) {
        if (p.type() == RewardType.SELL_QTY || p.type() == RewardType.BUY_QTY) {
            return Long.toString((long) Math.floor(p.target()));
        }
        return String.format(java.util.Locale.US, "%.2f", p.target());
    }

    private String formatProgress(Progress p) {
        if (p.type() == RewardType.SELL_QTY || p.type() == RewardType.BUY_QTY) {
            return Long.toString((long) Math.floor(p.progress()));
        }
        return String.format(java.util.Locale.US, "%.2f", p.progress());
    }

    private ItemStack namedItem(Material material, String name) {
        ItemStack stack = new ItemStack(material == null ? Material.PAPER : material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private String color(String s) {
        return s == null ? "" : ChatColor.translateAlternateColorCodes('&', s);
    }
}

