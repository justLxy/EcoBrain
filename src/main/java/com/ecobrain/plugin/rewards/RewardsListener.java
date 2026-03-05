package com.ecobrain.plugin.rewards;

import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class RewardsListener implements Listener {
    private final Plugin plugin;
    private final RewardsGUI rewardsGUI;
    private final RewardsManager rewardsManager;
    private final MarketViewGUI marketViewGUI;
    private final ItemMarketRepository marketRepository;
    private final RewardClaimRepository claimRepository;
    private final RewardCommandRunner commandRunner;

    public RewardsListener(Plugin plugin,
                           RewardsGUI rewardsGUI,
                           RewardsManager rewardsManager,
                           MarketViewGUI marketViewGUI,
                           ItemMarketRepository marketRepository,
                           RewardClaimRepository claimRepository,
                           RewardCommandRunner commandRunner) {
        this.plugin = plugin;
        this.rewardsGUI = rewardsGUI;
        this.rewardsManager = rewardsManager;
        this.marketViewGUI = marketViewGUI;
        this.marketRepository = marketRepository;
        this.claimRepository = claimRepository;
        this.commandRunner = commandRunner;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (!rewardsGUI.isRewardsTitle(event.getView().getTitle())) {
            return;
        }
        event.setCancelled(true);

        RewardsGUI.Session session = rewardsGUI.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= session.size()) {
            return;
        }

        RewardsConfig cfg = rewardsManager.config();
        if (rawSlot == cfg.gui().backSlot()) {
            // 返回市场大盘
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                List<ItemMarketRecord> records = marketRepository.findAll();
                List<ItemMarketRecord> filtered = marketViewGUI.filterAndSort(records, player.getUniqueId());
                double treasury = ItemMarketRepository.centsToMoney(marketRepository.getTreasuryBalanceCents());
                Bukkit.getScheduler().runTask(plugin, () -> marketViewGUI.open(player, filtered, 1, treasury));
            });
            return;
        }
        rewardsGUI.categoryAtSlot(rawSlot).ifPresent(cat -> {
            rewardsGUI.setCategory(player.getUniqueId(), cat);
            rewardsGUI.open(player, 1);
        });
        if (rewardsGUI.categoryAtSlot(rawSlot).isPresent()) {
            return;
        }
        if (rawSlot == RewardsGUI.PREV_PAGE_SLOT && session.page() > 1) {
            rewardsGUI.open(player, session.page() - 1);
            return;
        }
        if (rawSlot == RewardsGUI.NEXT_PAGE_SLOT && session.page() < session.maxPage()) {
            rewardsGUI.open(player, session.page() + 1);
            return;
        }

        Map<Integer, String> map = session.rewardIdAtSlot();
        String rewardId = map.get(rawSlot);
        if (rewardId == null) {
            return;
        }

        Optional<RewardDefinition> opt = cfg.rewards().stream().filter(r -> r.id().equals(rewardId)).findFirst();
        if (opt.isEmpty()) {
            player.sendMessage(ChatColor.RED + "该奖励不存在或已被移除。");
            return;
        }
        RewardDefinition def = opt.get();

        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean alreadyClaimed = claimRepository.getClaimedRewardIds(uuid).contains(def.id());
            if (alreadyClaimed) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.GRAY + "你已经领取过该奖励了。"));
                return;
            }

            double progress = switch (def.type()) {
                case SELL_MONEY -> marketRepository.getPlayerTotalMoney(uuid, TradeType.SELL);
                case SELL_QTY -> (double) marketRepository.getPlayerTotalQuantity(uuid, TradeType.SELL);
                case BUY_MONEY -> marketRepository.getPlayerTotalMoney(uuid, TradeType.BUY);
                case BUY_QTY -> (double) marketRepository.getPlayerTotalQuantity(uuid, TradeType.BUY);
            };
            if (progress < def.target()) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.RED + "你尚未达成该奖励条件。"));
                return;
            }

            boolean claimedNow = claimRepository.tryClaim(uuid, def.id(), System.currentTimeMillis());
            if (!claimedNow) {
                Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(ChatColor.GRAY + "你已经领取过该奖励了。"));
                return;
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                player.sendMessage(ChatColor.GREEN + "奖励领取成功！");
                commandRunner.run(player, def.commands());
                rewardsGUI.open(player, session.page());
            });
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (rewardsGUI.isRewardsTitle(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }
}

