package com.ecobrain.plugin.service;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.DiscoveryStage;
import com.ecobrain.plugin.model.DiscoveryState;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.DatabaseManager;
import com.ecobrain.plugin.persistence.ItemMarketRepository;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

class StatisticalPriceDiscoveryServiceTest {

    @Test
    void unknownItemsShouldStartWithLowBidAndWideAsk() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("unknown", "base64", 100.0D, 1.0D, 64, 64, 64);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        ItemMarketRecord record = repository.findByHash("unknown").orElseThrow();
        var snapshot = service.snapshot(record);
        var sellQuote = service.quoteSell(record, 16);

        Assertions.assertTrue(snapshot.bidPrice() < 40.0D, "unknown item bid should be far below 100 anchor");
        Assertions.assertTrue(snapshot.askPrice() > 200.0D, "unknown item ask should stay wide");
        Assertions.assertTrue((sellQuote.totalPrice() / 16.0D) < 35.0D, "unknown item average sell payout should stay low");
    }

    @Test
    void distributedBuysShouldPromoteItemToDiscovery() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("mat", "base64", 100.0D, 1.0D, 256, 256, 256);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        long now = System.currentTimeMillis();
        int remainingPhysical = 256;
        for (int i = 0; i < 5; i++) {
            UUID buyer = UUID.randomUUID();
            int quantity = 8;
            double totalPrice = 2400.0D;
            remainingPhysical -= quantity;
            repository.updateStocks("mat", Math.max(1, 256 - ((i + 1) * quantity)), remainingPhysical);
            repository.recordTrade("mat", TradeType.BUY, quantity, totalPrice, now + i);
            repository.recordPlayerTransaction(buyer, "buyer-" + i, TradeType.BUY, "mat", quantity, totalPrice, now + i);
            ItemMarketRecord record = repository.findByHash("mat").orElseThrow();
            service.ingestTradeEvidence(record, TradeType.BUY, quantity, totalPrice, buyer, now + i);
        }

        ItemMarketRecord latest = repository.findByHash("mat").orElseThrow();
        DiscoveryState state = service.currentState(latest);
        Assertions.assertEquals(DiscoveryStage.DISCOVERY, state.stage());
        Assertions.assertTrue(state.distinctBuyers7d() >= 5);
        Assertions.assertTrue(state.trustedBuyQty7d() >= 16.0D);
    }

    @Test
    void reversalTradeFromSamePlayerShouldNotMovePosteriorTwice() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("wash", "base64", 100.0D, 1.0D, 128, 128, 128);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        UUID player = UUID.randomUUID();
        long now = System.currentTimeMillis();

        repository.recordTrade("wash", TradeType.BUY, 1, 1000.0D, now);
        repository.recordPlayerTransaction(player, "wash-player", TradeType.BUY, "wash", 1, 1000.0D, now);
        ItemMarketRecord afterBuyRecord = repository.findByHash("wash").orElseThrow();
        service.ingestTradeEvidence(afterBuyRecord, TradeType.BUY, 1, 1000.0D, player, now);
        double muAfterBuy = service.currentState(afterBuyRecord).muLogPrice();

        repository.recordTrade("wash", TradeType.SELL, 1, 10.0D, now + 1_000L);
        repository.recordPlayerTransaction(player, "wash-player", TradeType.SELL, "wash", 1, 10.0D, now + 1_000L);
        ItemMarketRecord afterSellRecord = repository.findByHash("wash").orElseThrow();
        service.ingestTradeEvidence(afterSellRecord, TradeType.SELL, 1, 10.0D, player, now + 1_000L);
        double muAfterSell = service.currentState(afterSellRecord).muLogPrice();

        Assertions.assertEquals(muAfterBuy, muAfterSell, 1.0e-9, "reversal trade should be weight-zero");
    }

    @Test
    void matureQuotesShouldRespondToInventoryPressure() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("rare", "base64", 100.0D, 1.0D, 100, 100, 100);
        repository.saveDiscoveryState(new DiscoveryState(
            "rare",
            Math.log(5000.0D),
            0.20D,
            DiscoveryStage.MATURE,
            0.05D,
            120.0D,
            80.0D,
            16,
            12,
            0.20D,
            0.02D,
            100.0D,
            40.0D,
            System.currentTimeMillis()
        ));
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        ItemMarketRecord highStock = new ItemMarketRecord("rare", "base64", 100.0D, 1.0D, 100, 100, 100);
        ItemMarketRecord lowStock = new ItemMarketRecord("rare", "base64", 100.0D, 1.0D, 100, 100, 10);

        double askHighStock = service.snapshot(highStock).askPrice();
        double askLowStock = service.snapshot(lowStock).askPrice();

        Assertions.assertTrue(askLowStock > askHighStock, "ask should rise when physical stock falls");
    }

    private ItemMarketRepository newRepository() throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        var tempDir = Files.createTempDirectory("ecobrain-discovery-test-");
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DatabaseManager databaseManager = new DatabaseManager(plugin);
        databaseManager.initializeSchema();
        return new ItemMarketRepository(databaseManager);
    }

    private PluginSettings defaultSettings() {
        return new PluginSettings(
            new PluginSettings.Economy(100.0D, 1.0D, true, 500_000.0D),
            new PluginSettings.Trade(1_500L),
            new PluginSettings.CircuitBreaker(1.0D, 1),
            new PluginSettings.AI(false, 15, 24, 3, 0.12D, 0.35D, 0.10D, 0.2D, 6.0D, 100_000.0D,
                new PluginSettings.AdaptiveTarget(true, 0.01D, 10)),
            new PluginSettings.Discovery(100.0D, 2.3D, 0.35D, 3, 16.0D, 10, 64.0D),
            new PluginSettings.Evidence(7, 24, 2, 30, 3, 0.5D, 1.0D, 0.35D, 1.0D, 0.8D, 1.0D, 1.0D),
            new PluginSettings.AntiManipulation(0.60D, 0.25D, 0.70D, 0.85D, 0.50D, 0.45D),
            new PluginSettings.MarketMaker(0.15D, 0.60D, 0.40D, 1.40D, 0.50D, 0.08D, 0.20D, 16.0D, 16.0D, 2048.0D, 0.05D, 0.10D, 0.60D, 0.50D),
            new PluginSettings.Gui(
                "bulk",
                Material.LIME_STAINED_GLASS_PANE, "sell", List.of(),
                Material.RED_STAINED_GLASS_PANE, "cancel", List.of(),
                Material.BLACK_STAINED_GLASS_PANE,
                List.of()
            )
        );
    }
}
