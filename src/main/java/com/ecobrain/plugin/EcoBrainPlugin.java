package com.ecobrain.plugin;

import com.ecobrain.plugin.ai.AIScheduler;
import com.ecobrain.plugin.ai.DqnTrainer;
import com.ecobrain.plugin.ai.NeuralNet;
import com.ecobrain.plugin.ai.ReplayBuffer;
import org.bukkit.scheduler.BukkitTask;
import com.ecobrain.plugin.command.AdminCommand;
import com.ecobrain.plugin.command.EcoBrainCommand;
import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
import com.ecobrain.plugin.gui.LeaderboardGUI;
import com.ecobrain.plugin.listener.BulkSellListener;
import com.ecobrain.plugin.listener.MarketViewListener;
import com.ecobrain.plugin.persistence.DatabaseManager;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import com.ecobrain.plugin.serialization.ItemSerializer;
import com.ecobrain.plugin.service.AMMCalculator;
import com.ecobrain.plugin.service.EconomyService;
import com.ecobrain.plugin.service.MarketService;
import com.ecobrain.plugin.safety.CircuitBreaker;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * EcoBrain 插件主入口。
 */
public class EcoBrainPlugin extends JavaPlugin {
    private static EcoBrainPlugin instance;

    private ItemSerializer itemSerializer;
    private DatabaseManager databaseManager;
    private ItemMarketRepository repository;
    private com.ecobrain.plugin.placeholder.PlaceholderApiHook placeholderApiHook;
    private com.ecobrain.plugin.rewards.RewardsManager rewardsManager;
    private com.ecobrain.plugin.rewards.RewardsGUI rewardsGUI;
    private EconomyService economyService;
    private AMMCalculator ammCalculator;
    private CircuitBreaker circuitBreaker;
    private MarketService marketService;
    private BulkSellGUI bulkSellGUI;
    private MarketViewGUI marketViewGUI;
    private LeaderboardGUI leaderboardGUI;
    private EcoBrainCommand ecoBrainCommand;
    private AIScheduler aiScheduler;
    private NeuralNet neuralNet;
    private DqnTrainer dqnTrainer;
    private BukkitTask aiAutosaveTask;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        PluginSettings settings = PluginSettings.load(this);

        this.itemSerializer = new ItemSerializer();
        this.databaseManager = new DatabaseManager(this);
        this.repository = new ItemMarketRepository(databaseManager);
        databaseManager.initializeSchema();

        this.economyService = new EconomyService(this);
        if (!economyService.setup()) {
            getLogger().warning("Vault 未就绪，EcoBrain 将无法处理经济交易。");
        }

        this.ammCalculator = new AMMCalculator();
        this.circuitBreaker = new CircuitBreaker(repository, ammCalculator, settings.circuitBreaker());
        this.marketService = new MarketService(this, repository, ammCalculator, circuitBreaker, settings.economy());
        this.bulkSellGUI = new BulkSellGUI(settings.gui());
        this.marketViewGUI = new MarketViewGUI(ammCalculator, itemSerializer, settings.gui());
        this.leaderboardGUI = new LeaderboardGUI();
        this.rewardsManager = new com.ecobrain.plugin.rewards.RewardsManager(this);
        com.ecobrain.plugin.rewards.RewardClaimRepository rewardClaimRepository = new com.ecobrain.plugin.rewards.RewardClaimRepository(databaseManager);
        this.rewardsGUI = new com.ecobrain.plugin.rewards.RewardsGUI(this, rewardsManager, repository, rewardClaimRepository);
        com.ecobrain.plugin.rewards.RewardCommandRunner rewardCommandRunner = new com.ecobrain.plugin.rewards.RewardCommandRunner(this);
        AdminCommand adminCommand = new AdminCommand(this, repository, itemSerializer);

        this.ecoBrainCommand = new EcoBrainCommand(
            this,
            itemSerializer,
            repository,
            marketService,
            economyService,
            bulkSellGUI,
            marketViewGUI,
            rewardsGUI,
            adminCommand,
            settings.trade().cooldownMs()
        );
        if (getCommand("ecobrain") != null) {
            getCommand("ecobrain").setExecutor(ecoBrainCommand);
            getCommand("ecobrain").setTabCompleter(ecoBrainCommand);
        }
        Bukkit.getPluginManager().registerEvents(
            new BulkSellListener(this, bulkSellGUI, itemSerializer, marketService, economyService), this);
        Bukkit.getPluginManager().registerEvents(
            new MarketViewListener(this, marketViewGUI, bulkSellGUI, leaderboardGUI, rewardsGUI, repository, marketService, economyService, itemSerializer), this);
        Bukkit.getPluginManager().registerEvents(
            new com.ecobrain.plugin.listener.LeaderboardListener(this, leaderboardGUI, marketViewGUI, repository), this);
        Bukkit.getPluginManager().registerEvents(
            new com.ecobrain.plugin.rewards.RewardsListener(this, rewardsGUI, rewardsManager, marketViewGUI, repository, rewardClaimRepository, rewardCommandRunner), this);

        this.placeholderApiHook = new com.ecobrain.plugin.placeholder.PlaceholderApiHook(this, repository);
        this.placeholderApiHook.registerIfPresent();

        com.ecobrain.plugin.ai.AiModelStore store = new com.ecobrain.plugin.ai.AiModelStore();
        java.util.Optional<com.ecobrain.plugin.ai.AiModelStore.Snapshot> snapshotOpt = store.load(this);
        if (snapshotOpt.isPresent()) {
            com.ecobrain.plugin.ai.AiModelStore.Snapshot snapshot = snapshotOpt.get();
            try {
                this.neuralNet = NeuralNet.fromState(snapshot.netState());
                this.dqnTrainer = new DqnTrainer(this.neuralNet, new ReplayBuffer(settings.ai().replayBufferCapacity()), 3);
                this.dqnTrainer.setEpsilon(snapshot.epsilon());
                getLogger().info("[EcoBrain-AI] 已加载持久化模型，epsilon=" + String.format("%.4f", this.dqnTrainer.getEpsilon()));
            } catch (Exception e) {
                getLogger().warning("[EcoBrain-AI] 持久化模型加载失败，回退到冷启动: " + e.getMessage());
                this.neuralNet = new NeuralNet(3, 16, 8, 3, System.currentTimeMillis());
                this.dqnTrainer = new DqnTrainer(this.neuralNet, new ReplayBuffer(settings.ai().replayBufferCapacity()), 3);
            }
        } else {
            this.neuralNet = new NeuralNet(3, 16, 8, 3, System.currentTimeMillis());
            this.dqnTrainer = new DqnTrainer(this.neuralNet, new ReplayBuffer(settings.ai().replayBufferCapacity()), 3);
        }

        this.aiScheduler = new AIScheduler(this, this.dqnTrainer, repository, settings.ai(), itemSerializer);
        this.aiScheduler.setFullSettings(settings);
        this.aiScheduler.start();
        restartAiAutosave(settings.ai());

        getLogger().info("EcoBrain 已启用。");
    }

    @Override
    public void onDisable() {
        if (aiAutosaveTask != null) {
            aiAutosaveTask.cancel();
            aiAutosaveTask = null;
        }
        if (aiScheduler != null) {
            aiScheduler.stop();
        }
        if (placeholderApiHook != null) {
            placeholderApiHook.shutdown();
        }
        saveAiSnapshot();
        getLogger().info("EcoBrain 已关闭。");
    }

    public static EcoBrainPlugin getInstance() {
        return instance;
    }

    public ItemSerializer getItemSerializer() {
        return itemSerializer;
    }

    /**
     * 热更新入口：
     * 1) 重新读取 config.yml
     * 2) 下发运行时参数到各组件
     * 3) 重启 AI 定时任务使新周期立即生效
     */
    public synchronized void reloadRuntimeSettings() {
        reloadConfig();
        PluginSettings settings = PluginSettings.load(this);

        marketService.updateEconomySettings(settings.economy());
        circuitBreaker.updateSettings(settings.circuitBreaker());
        bulkSellGUI.applySettings(settings.gui());
        marketViewGUI.applySettings(settings.gui());
        ecoBrainCommand.updateCooldown(settings.trade().cooldownMs());
        if (rewardsManager != null) {
            rewardsManager.reload();
        }
        aiScheduler.updateSettingsAndRestart(settings.ai(), settings);
        restartAiAutosave(settings.ai());
    }

    private void restartAiAutosave(PluginSettings.AI aiSettings) {
        if (aiAutosaveTask != null) {
            aiAutosaveTask.cancel();
            aiAutosaveTask = null;
        }
        int minutes = aiSettings == null ? 0 : aiSettings.modelAutosaveMinutes();
        if (minutes <= 0) {
            return;
        }
        long periodTicks = 20L * 60L * Math.max(1, minutes);
        aiAutosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::saveAiSnapshot, periodTicks, periodTicks);
    }

    private void saveAiSnapshot() {
        if (neuralNet == null || dqnTrainer == null) {
            return;
        }
        try {
            com.ecobrain.plugin.ai.AiModelStore store = new com.ecobrain.plugin.ai.AiModelStore();
            store.save(this, new com.ecobrain.plugin.ai.AiModelStore.Snapshot(
                1,
                neuralNet.exportState(),
                dqnTrainer.getEpsilon(),
                System.currentTimeMillis()
            ));
        } catch (Exception e) {
            getLogger().warning("[EcoBrain-AI] 自动保存失败: " + e.getMessage());
        }
    }
}
