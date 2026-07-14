package com.ecobrain.plugin.service;

import com.ecobrain.plugin.config.PluginSettings;
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

/**
 * v5 定价引擎（卡尔曼滤波 + 鲁棒似然 + Avellaneda-Stoikov）契约测试。
 */
class StatisticalPriceDiscoveryServiceTest {

    @Test
    void coldStartMidPriceShouldSitAtAnchor() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("fresh", "base64", 64);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        ItemMarketRecord record = repository.findByHash("fresh").orElseThrow();
        var snapshot = service.snapshot(record);

        // 冷启动锚点 100，中性中价应贴近锚点。
        Assertions.assertEquals(100.0D, snapshot.midPrice(), 5.0D, "cold-start mid should sit at anchor");
        // 初始不确定性大 → 价差宽 → ask 明显高于 bid。
        Assertions.assertTrue(snapshot.askPrice() > snapshot.bidPrice(), "ask must exceed bid");
        Assertions.assertTrue(snapshot.halfSpread() > 0.0D, "half-spread must be positive");
    }

    @Test
    void repeatedConsistentBuysShouldPullFairValueTowardObservedPrice() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("conv", "base64", 512);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        long now = System.currentTimeMillis();
        double observedUnit = 800.0D;
        for (int i = 0; i < 12; i++) {
            UUID buyer = UUID.randomUUID(); // 多样化交易者，避免多样性惩罚
            int qty = 8;
            double total = observedUnit * qty;
            repository.recordTrade("conv", TradeType.BUY, qty, total, now + i * 1000L);
            repository.recordPlayerTransaction(buyer, "b" + i, TradeType.BUY, "conv", qty, total, now + i * 1000L);
            ItemMarketRecord rec = repository.findByHash("conv").orElseThrow();
            service.ingestTradeEvidence(rec, TradeType.BUY, qty, total, buyer, now + i * 1000L);
        }

        DiscoveryState state = service.currentState(repository.findByHash("conv").orElseThrow());
        double mid = Math.exp(state.xLogValue());
        // 后验应从锚点 100 明显朝 800 收敛（不必到达，但要越过中点）。
        Assertions.assertTrue(mid > 300.0D, "fair value should move substantially toward observed 800, got " + mid);
        // 一致成交后不确定性应下降到低于冷启动初值。
        Assertions.assertTrue(state.pVar() < 5.0D, "variance should shrink after consistent evidence, got " + state.pVar());
    }

    @Test
    void outlierWashTradeShouldBeDownWeighted() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("wash", "base64", 512);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        long now = System.currentTimeMillis();
        // 先用一致成交把公允价稳定在 ~200 附近。
        for (int i = 0; i < 10; i++) {
            UUID buyer = UUID.randomUUID();
            double total = 200.0D * 8;
            repository.recordTrade("wash", TradeType.BUY, 8, total, now + i * 1000L);
            repository.recordPlayerTransaction(buyer, "b" + i, TradeType.BUY, "wash", 8, total, now + i * 1000L);
            service.ingestTradeEvidence(repository.findByHash("wash").orElseThrow(), TradeType.BUY, 8, total, buyer, now + i * 1000L);
        }
        double midBefore = Math.exp(service.currentState(repository.findByHash("wash").orElseThrow()).xLogValue());

        // 注入一笔极端离群成交（单价 100000），鲁棒似然应几乎不理它。
        UUID manipulator = UUID.randomUUID();
        long t = now + 20_000L;
        double crazyTotal = 100000.0D * 1;
        repository.recordTrade("wash", TradeType.BUY, 1, crazyTotal, t);
        repository.recordPlayerTransaction(manipulator, "m", TradeType.BUY, "wash", 1, crazyTotal, t);
        service.ingestTradeEvidence(repository.findByHash("wash").orElseThrow(), TradeType.BUY, 1, crazyTotal, manipulator, t);

        double midAfter = Math.exp(service.currentState(repository.findByHash("wash").orElseThrow()).xLogValue());
        double relativeJump = Math.abs(midAfter - midBefore) / midBefore;
        Assertions.assertTrue(relativeJump < 0.5D,
            "single extreme outlier must not move fair value much (jump=" + relativeJump + ", before=" + midBefore + ", after=" + midAfter + ")");
    }

    @Test
    void askShouldRiseWhenPhysicalStockFalls() throws Exception {
        ItemMarketRepository repository = newRepository();
        repository.upsertIpo("rare", "base64", 100);
        StatisticalPriceDiscoveryService service = new StatisticalPriceDiscoveryService(repository, defaultSettings());

        ItemMarketRecord highStock = new ItemMarketRecord("rare", "base64", 100);
        ItemMarketRecord lowStock = new ItemMarketRecord("rare", "base64", 2);

        double askHigh = service.snapshot(highStock).askPrice();
        service.invalidateSnapshot("rare"); // 库存不同，绕过 TTL 缓存
        double askLow = service.snapshot(lowStock).askPrice();

        Assertions.assertTrue(askLow > askHigh,
            "ask should rise when physical stock falls (low=" + askLow + ", high=" + askHigh + ")");
    }

    private ItemMarketRepository newRepository() throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        var tempDir = Files.createTempDirectory("ecobrain-discovery-test-");
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DatabaseManager databaseManager = new DatabaseManager(plugin);
        databaseManager.initializeSchema();
        // 金库给足，避免 treasury shortfall 干扰定价断言。
        ItemMarketRepository repo = new ItemMarketRepository(databaseManager);
        repo.creditTreasuryCents(ItemMarketRepository.moneyToCents(100_000_000.0D));
        return repo;
    }

    private PluginSettings defaultSettings() {
        return new PluginSettings(
            new PluginSettings.Economy(100.0D, 1.0D, true, 500_000.0D),
            new PluginSettings.Trade(1_500L),
            new PluginSettings.CircuitBreaker(1.0D, 1),
            new PluginSettings.Pricing(
                0.15D,   // gamma
                0.02D,   // process-noise-per-hour
                1.0D,    // observation-noise-base
                4.0D,    // student-t-dof
                12.0D,   // volatility-half-life-hours
                0.05D,   // base-fee
                1.0D,    // inventory-risk-weight
                1.0D,    // treasury-risk-weight
                16.0D,   // trusted-float-baseline
                16.0D,   // min-depth
                2048.0D, // max-depth
                100.0D,  // anchor-price
                5.0D,    // initial-variance
                24,      // diversity-window-hours
                7        // garbage-collection-days
            ),
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
