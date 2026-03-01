package com.ecobrain.plugin;

import com.ecobrain.plugin.ai.AIScheduler;
import com.ecobrain.plugin.ai.DqnTrainer;
import com.ecobrain.plugin.ai.NeuralNet;
import com.ecobrain.plugin.ai.ReplayBuffer;
import com.ecobrain.plugin.command.AdminCommand;
import com.ecobrain.plugin.command.EcoBrainCommand;
import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.gui.BulkSellGUI;
import com.ecobrain.plugin.gui.MarketViewGUI;
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
    private EconomyService economyService;
    private AMMCalculator ammCalculator;
    private CircuitBreaker circuitBreaker;
    private MarketService marketService;
    private BulkSellGUI bulkSellGUI;
    private MarketViewGUI marketViewGUI;
    private EcoBrainCommand ecoBrainCommand;
    private AIScheduler aiScheduler;

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
        AdminCommand adminCommand = new AdminCommand(this, repository);

        this.ecoBrainCommand = new EcoBrainCommand(
            this,
            itemSerializer,
            repository,
            marketService,
            economyService,
            bulkSellGUI,
            marketViewGUI,
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
            new MarketViewListener(this, marketViewGUI, bulkSellGUI, repository, marketService, economyService, itemSerializer), this);

        NeuralNet neuralNet = new NeuralNet(3, 16, 8, 3, System.currentTimeMillis());
        DqnTrainer dqnTrainer = new DqnTrainer(neuralNet, new ReplayBuffer(settings.ai().replayBufferCapacity()), 3);
        this.aiScheduler = new AIScheduler(this, dqnTrainer, repository, settings.ai(), itemSerializer);
        this.aiScheduler.start();

        getLogger().info("EcoBrain 已启用。");
    }

    @Override
    public void onDisable() {
        if (aiScheduler != null) {
            aiScheduler.stop();
        }
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
        aiScheduler.updateSettingsAndRestart(settings.ai());
    }
}
