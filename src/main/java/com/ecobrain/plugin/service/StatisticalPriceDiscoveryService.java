package com.ecobrain.plugin.service;

import com.ecobrain.plugin.config.PluginSettings;
import com.ecobrain.plugin.model.DiscoveryStage;
import com.ecobrain.plugin.model.DiscoveryState;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.MarketSnapshot;
import com.ecobrain.plugin.model.TradeType;
import com.ecobrain.plugin.persistence.ItemMarketRepository;

import java.util.UUID;

/**
 * 纯统计学习价值发现器。
 * 主价格链路只读这套状态，不再依赖 base_price/k 作为市场成交价中心。
 */
public class StatisticalPriceDiscoveryService {
    private final ItemMarketRepository repository;

    private volatile PluginSettings.Discovery discoverySettings;
    private volatile PluginSettings.Evidence evidenceSettings;
    private volatile PluginSettings.AntiManipulation antiManipulationSettings;
    private volatile PluginSettings.MarketMaker marketMakerSettings;

    public StatisticalPriceDiscoveryService(ItemMarketRepository repository, PluginSettings settings) {
        this.repository = repository;
        updateSettings(settings);
    }

    public void updateSettings(PluginSettings settings) {
        this.discoverySettings = settings.discovery();
        this.evidenceSettings = settings.evidence();
        this.antiManipulationSettings = settings.antiManipulation();
        this.marketMakerSettings = settings.marketMaker();
    }

    public MarketSnapshot snapshot(ItemMarketRecord record) {
        DiscoveryState baseState = repository.getOrCreateDiscoveryState(record.getItemHash());
        DiscoveryState computed = computeRollingState(record, baseState, System.currentTimeMillis());
        return toSnapshot(record, computed);
    }

    public DiscoveryState currentState(ItemMarketRecord record) {
        DiscoveryState baseState = repository.getOrCreateDiscoveryState(record.getItemHash());
        return computeRollingState(record, baseState, System.currentTimeMillis());
    }

    public void ingestTradeEvidence(ItemMarketRecord record,
                                    TradeType tradeType,
                                    int amount,
                                    double totalPrice,
                                    UUID playerUuid,
                                    long now) {
        DiscoveryState rolling = computeRollingState(record, repository.getOrCreateDiscoveryState(record.getItemHash()), now);
        double observedUnitPrice = amount <= 0 ? 0.0D : (totalPrice / amount);
        double observedLogPrice = Math.log(Math.max(1.0e-6D, observedUnitPrice));

        double tradeWeight = computeTradeWeight(record.getItemHash(), rolling, tradeType, playerUuid, now);
        if (tradeType == TradeType.SELL && rolling.stage() == DiscoveryStage.UNKNOWN) {
            observedLogPrice = Math.min(observedLogPrice, rolling.muLogPrice());
        }
        if (rolling.manipulationScore() > antiManipulationSettings.freezeUpsideScore()
            && observedLogPrice > rolling.muLogPrice()) {
            tradeWeight = 0.0D;
        }

        double newMu = rolling.muLogPrice();
        double newSigma = rolling.sigmaLogPrice();
        if (tradeWeight > 0.0D && Double.isFinite(observedLogPrice)) {
            double observationSigma = observationSigmaFor(amount, tradeType);
            double priorPrecision = 1.0D / Math.max(1.0e-6D, rolling.sigmaLogPrice() * rolling.sigmaLogPrice());
            double evidencePrecision = tradeWeight / Math.max(1.0e-6D, observationSigma * observationSigma);
            double combinedPrecision = priorPrecision + evidencePrecision;
            newMu = ((rolling.muLogPrice() * priorPrecision) + (observedLogPrice * evidencePrecision)) / combinedPrecision;
            newSigma = Math.sqrt(1.0D / combinedPrecision);
            if (tradeType == TradeType.SELL && rolling.stage() == DiscoveryStage.UNKNOWN) {
                newMu = Math.min(newMu, rolling.muLogPrice());
            }
            newSigma = clamp(newSigma, 0.18D, 3.0D);
        }

        DiscoveryState posterior = new DiscoveryState(
            rolling.itemHash(),
            newMu,
            newSigma,
            rolling.stage(),
            rolling.manipulationScore(),
            rolling.trustedBuyQty7d(),
            rolling.trustedSellQty7d(),
            rolling.distinctBuyers7d(),
            rolling.distinctSellers7d(),
            rolling.top2FlowShare24h(),
            rolling.reversalRate24h(),
            rolling.trustedFloat(),
            rolling.liquidityDepth(),
            now
        );
        DiscoveryState finalState = computeRollingState(record, posterior, now);
        repository.saveDiscoveryState(finalState);
    }

    public QuoteComputation quoteBuy(ItemMarketRecord record, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        MarketSnapshot snapshot = snapshot(record);
        double total = 0.0D;
        for (int i = 0; i < amount; i++) {
            double projectedPhysical = Math.max(0.0D, record.getPhysicalStock() - i);
            total += marginalPrice(snapshot, projectedPhysical, i, TradeType.BUY);
        }
        int compatibilityInventory = Math.max(1, record.getCurrentInventory() - amount);
        return new QuoteComputation(Math.max(0.0D, total), compatibilityInventory, snapshot);
    }

    public QuoteComputation quoteSell(ItemMarketRecord record, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        MarketSnapshot snapshot = snapshot(record);
        double total = 0.0D;
        for (int i = 0; i < amount; i++) {
            double projectedPhysical = Math.max(0.0D, record.getPhysicalStock() + i);
            total += marginalPrice(snapshot, projectedPhysical, i, TradeType.SELL);
        }
        double backstopSpread = calculateSellBackstopSpread(snapshot, amount);
        total *= Math.max(0.0D, 1.0D - backstopSpread);
        int compatibilityInventory = Math.max(1, record.getCurrentInventory() + amount);
        return new QuoteComputation(Math.max(0.0D, total), compatibilityInventory, snapshot);
    }

    private DiscoveryState computeRollingState(ItemMarketRecord record, DiscoveryState state, long now) {
        long buySellSince = now - daysToMillis(evidenceSettings.buyersWindowDays());
        long flowSince = now - hoursToMillis(evidenceSettings.flowWindowHours());
        long reversalSince = flowSince;
        long reversalWindow = hoursToMillis(evidenceSettings.reversalWindowHours());

        ItemMarketRepository.DiscoveryWindowStats stats = repository.queryDiscoveryWindowStats(
            record.getItemHash(),
            buySellSince,
            flowSince,
            reversalSince,
            reversalWindow
        );

        double manipulationScore = computeManipulationScore(state, stats);
        double trustDiscount = computeTrustDiscount(stats, manipulationScore);
        double trustedBuyQty = stats.buyQty7d() * trustDiscount;
        double trustedSellQty = stats.sellQty7d() * trustDiscount;
        double trustedFloat = computeTrustedFloat(record, trustedBuyQty, trustedSellQty);
        DiscoveryStage stage = computeStage(state.stage(), state.sigmaLogPrice(), trustedBuyQty, stats, manipulationScore);
        double liquidityDepth = computeLiquidityDepth(stage, trustedFloat);

        return new DiscoveryState(
            state.itemHash(),
            state.muLogPrice(),
            state.sigmaLogPrice(),
            stage,
            manipulationScore,
            trustedBuyQty,
            trustedSellQty,
            stats.distinctBuyers7d(),
            stats.distinctSellers7d(),
            stats.top2FlowShare24h(),
            stats.reversalRate24h(),
            trustedFloat,
            liquidityDepth,
            now
        );
    }

    private DiscoveryStage computeStage(DiscoveryStage currentStage,
                                        double sigmaLogPrice,
                                        double trustedBuyQty,
                                        ItemMarketRepository.DiscoveryWindowStats stats,
                                        double manipulationScore) {
        boolean canEnterDiscovery = stats.distinctBuyers7d() >= discoverySettings.unknownToDiscoveryMinDistinctBuyers7d()
            && trustedBuyQty >= discoverySettings.unknownToDiscoveryMinTrustedBuyQty7d()
            && manipulationScore < antiManipulationSettings.discoveryEntryMaxScore();

        boolean canEnterMature = stats.distinctBuyers7d() >= discoverySettings.discoveryToMatureMinDistinctBuyers7d()
            && trustedBuyQty >= discoverySettings.discoveryToMatureMinTrustedBuyQty7d()
            && sigmaLogPrice <= discoverySettings.discoverySigmaThreshold()
            && stats.top2FlowShare24h() <= antiManipulationSettings.matureMaxTop2Share()
            && manipulationScore <= antiManipulationSettings.promotionBlockScore();

        if (currentStage == DiscoveryStage.MATURE && !canEnterMature) {
            return DiscoveryStage.DISCOVERY;
        }
        if (canEnterMature) {
            return DiscoveryStage.MATURE;
        }
        if (currentStage == DiscoveryStage.UNKNOWN && canEnterDiscovery) {
            return DiscoveryStage.DISCOVERY;
        }
        if (currentStage == DiscoveryStage.MATURE) {
            return DiscoveryStage.DISCOVERY;
        }
        return currentStage;
    }

    private double computeManipulationScore(DiscoveryState state, ItemMarketRepository.DiscoveryWindowStats stats) {
        double reversalComponent = clamp01(stats.reversalRate24h());
        double concentrationComponent = clamp01((stats.top2FlowShare24h() - 0.35D) / 0.65D);
        double breadthComponent = stats.distinctBuyers7d() >= 3
            ? 0.0D
            : clamp01((3.0D - stats.distinctBuyers7d()) / 3.0D);
        double priceWithoutBreadth = 0.0D;
        double elevatedLogPrice = Math.log(Math.max(1.0D, discoverySettings.anchorPrice() * 1.5D));
        if (state.muLogPrice() > elevatedLogPrice && stats.distinctBuyers7d() < 3) {
            priceWithoutBreadth = clamp01((state.muLogPrice() - elevatedLogPrice) / 1.5D);
        }
        return clamp01(
            (0.45D * reversalComponent)
                + (0.30D * concentrationComponent)
                + (0.15D * breadthComponent)
                + (0.10D * priceWithoutBreadth)
        );
    }

    private double computeTrustDiscount(ItemMarketRepository.DiscoveryWindowStats stats, double manipulationScore) {
        double concentrationPenalty = stats.top2FlowShare24h() > antiManipulationSettings.concentrationCapShare()
            ? antiManipulationSettings.concentrationCappedWeight()
            : 1.0D;
        double reversalPenalty = 1.0D - (0.75D * clamp01(stats.reversalRate24h()));
        double manipulationPenalty = 1.0D - (0.60D * clamp01(manipulationScore));
        return clamp(concentrationPenalty * reversalPenalty * manipulationPenalty, 0.20D, 1.0D);
    }

    private double computeTrustedFloat(ItemMarketRecord record, double trustedBuyQty, double trustedSellQty) {
        double baseline = Math.max(1.0D, marketMakerSettings.trustedFloatBaseline());
        double activityLimitedFloat = baseline + trustedBuyQty + trustedSellQty;
        return Math.max(1.0D, Math.min(Math.max(1, record.getPhysicalStock()), activityLimitedFloat));
    }

    private double computeLiquidityDepth(DiscoveryStage stage, double trustedFloat) {
        double depth = stage.stageMultiplier() * Math.sqrt(Math.max(1.0D, trustedFloat));
        return clamp(depth, marketMakerSettings.minDepth(), marketMakerSettings.maxDepth());
    }

    private MarketSnapshot toSnapshot(ItemMarketRecord record, DiscoveryState state) {
        double midPrice = Math.exp(state.muLogPrice());
        double targetStock = Math.max(1.0D, state.trustedFloat());
        double inventoryPressure = (targetStock - Math.max(0.0D, record.getPhysicalStock())) / targetStock;
        double reservationPrice = midPrice * Math.exp(marketMakerSettings.inventoryPressure() * inventoryPressure);
        double halfSpread = computeHalfSpread(state.stage(), state.sigmaLogPrice(), state.manipulationScore());
        double bidPrice = Math.max(0.01D, reservationPrice * Math.exp(-halfSpread));
        double askPrice = Math.max(bidPrice, reservationPrice * Math.exp(halfSpread));
        return new MarketSnapshot(
            record.getItemHash(),
            state.stage(),
            midPrice,
            reservationPrice,
            bidPrice,
            askPrice,
            halfSpread,
            state.sigmaLogPrice(),
            state.manipulationScore(),
            state.trustedBuyQty7d(),
            state.trustedSellQty7d(),
            state.distinctBuyers7d(),
            state.distinctSellers7d(),
            state.top2FlowShare24h(),
            state.reversalRate24h(),
            state.trustedFloat(),
            state.liquidityDepth()
        );
    }

    private double computeHalfSpread(DiscoveryStage stage, double sigmaLogPrice, double manipulationScore) {
        double floor = switch (stage) {
            case UNKNOWN -> marketMakerSettings.unknownHalfSpreadFloor();
            case DISCOVERY -> marketMakerSettings.discoveryHalfSpreadFloor();
            case MATURE -> marketMakerSettings.matureHalfSpreadFloor();
        };
        double halfSpread = Math.max(
            floor,
            (marketMakerSettings.sigmaSpreadMultiplier() * sigmaLogPrice)
                + (marketMakerSettings.manipulationSpreadMultiplier() * manipulationScore)
        );
        if (manipulationScore > antiManipulationSettings.freezeUpsideScore()) {
            halfSpread += 0.20D;
        }
        return halfSpread;
    }

    private double marginalPrice(MarketSnapshot snapshot, double projectedPhysicalStock, int stepIndex, TradeType tradeType) {
        double targetStock = Math.max(1.0D, snapshot.trustedFloat());
        double inventoryPressure = (targetStock - projectedPhysicalStock) / targetStock;
        double reservation = snapshot.midPrice() * Math.exp(marketMakerSettings.inventoryPressure() * inventoryPressure);
        double depth = Math.max(1.0D, snapshot.liquidityDepth());
        double slippage = Math.exp(marketMakerSettings.slippagePerDepth() * stepIndex / depth);
        return switch (tradeType) {
            case BUY -> reservation * Math.exp(snapshot.halfSpread()) * slippage;
            case SELL -> reservation * Math.exp(-snapshot.halfSpread()) / slippage;
        };
    }

    private double calculateSellBackstopSpread(MarketSnapshot snapshot, int amount) {
        double referenceDepth = Math.max(1.0D, Math.min(snapshot.liquidityDepth(), snapshot.trustedFloat()));
        double sellRatio = amount / referenceDepth;
        double extra = 0.0D;
        if (sellRatio > marketMakerSettings.sellBackstopTriggerRatio()) {
            extra = Math.min(
                marketMakerSettings.sellBackstopMaxExtra(),
                (sellRatio - marketMakerSettings.sellBackstopTriggerRatio()) * marketMakerSettings.sellBackstopSlope()
            );
        }
        return clamp(
            marketMakerSettings.sellBackstopBase() + extra,
            0.0D,
            0.95D
        );
    }

    private double computeTradeWeight(String itemHash,
                                      DiscoveryState state,
                                      TradeType tradeType,
                                      UUID playerUuid,
                                      long now) {
        double reversalWeight = 1.0D;
        double playerWeight = 1.0D;
        if (playerUuid != null) {
            long reversalSince = now - hoursToMillis(evidenceSettings.reversalWindowHours());
            if (repository.hasOppositePlayerTradeSince(playerUuid, itemHash, tradeType, reversalSince)) {
                reversalWeight = 0.0D;
            }
            long historySince = now - daysToMillis(evidenceSettings.playerHistoryWindowDays());
            int activeDays = repository.queryPlayerActiveDaysForItem(playerUuid, itemHash, historySince);
            if (activeDays < evidenceSettings.playerHistoryMinDays()) {
                playerWeight = evidenceSettings.newPlayerWeight();
            }
        }

        double concentrationWeight = state.top2FlowShare24h() > antiManipulationSettings.concentrationCapShare()
            ? antiManipulationSettings.concentrationCappedWeight()
            : 1.0D;

        double stageWeight = switch (state.stage()) {
            case UNKNOWN -> tradeType == TradeType.BUY
                ? evidenceSettings.unknownBuyWeight()
                : evidenceSettings.unknownSellWeight();
            case DISCOVERY -> tradeType == TradeType.BUY
                ? evidenceSettings.discoveryBuyWeight()
                : evidenceSettings.discoverySellWeight();
            case MATURE -> tradeType == TradeType.BUY
                ? evidenceSettings.matureBuyWeight()
                : evidenceSettings.matureSellWeight();
        };
        return clamp(reversalWeight * concentrationWeight * playerWeight * stageWeight, 0.0D, 1.25D);
    }

    private double observationSigmaFor(int amount, TradeType tradeType) {
        double sigma = 1.10D / Math.sqrt(Math.max(1, amount));
        if (tradeType == TradeType.SELL) {
            sigma *= 1.15D;
        }
        return clamp(sigma, 0.14D, 1.20D);
    }

    private static long daysToMillis(int days) {
        return Math.max(1L, days) * 24L * 60L * 60L * 1000L;
    }

    private static long hoursToMillis(int hours) {
        return Math.max(1L, hours) * 60L * 60L * 1000L;
    }

    private static double clamp01(double value) {
        return clamp(value, 0.0D, 1.0D);
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    public record QuoteComputation(double totalPrice, int compatibilityInventory, MarketSnapshot snapshot) {
    }
}
