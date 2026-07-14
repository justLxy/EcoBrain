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
 * 价值发现与做市定价引擎 (v5)。
 *
 * <h2>两层结构</h2>
 * <ol>
 *   <li><b>鲁棒卡尔曼滤波</b> 估计对数公允价值 {@code x} 及其方差 {@code P}：
 *     <ul>
 *       <li>时间更新：{@code P += q·Δt / diversity}，无成交时不确定性自然上升；
 *           交易者越集中（diversity 低）过程噪声越大 → 越不敢收敛，缓解单人刷价。</li>
 *       <li>测量更新：每笔成交是带噪观测，噪声 {@code R = r0/volume}；用 Student-t 的
 *           IRLS 权重 {@code w = (dof+1)/(dof + ν²/S)} 对创新降权 —— 洗价/对倒因创新巨大
 *           被自动打到接近零权重，<b>无需任何一条反操纵规则</b>。</li>
 *     </ul>
 *   </li>
 *   <li><b>Avellaneda-Stoikov 做市</b> 给出 bid/ask：
 *     reservation price 同时按库存失衡与金库紧张度偏移；half-spread 正比于总不确定性。
 *     金库吃紧时 bid 自动走低，而不是硬拒收。</li>
 * </ol>
 *
 * <h2>诚实边界</h2>
 * P2S 市场中系统是唯一对手方，观测源就是系统自己的报价，<b>循环论证是结构性的、任何算法都
 * 无法根除</b>。这里用交易者多样性调节过程噪声来缓解单人推价的正反馈，但这是缓解不是根治。
 */
public class StatisticalPriceDiscoveryService {
    private final ItemMarketRepository repository;

    private volatile PluginSettings.Pricing pricing;

    /**
     * 只读快照的短 TTL 缓存。市场大盘 GUI 会对每个物品调用一次 {@link #snapshot}，缓存可将
     * “打开/翻页/排序大盘”的数据库压力降到接近 0。快照是近似展示值，短时间复用不影响体验。
     * 缓存键含 physicalStock，成交/参数变更会主动失效。
     */
    private final java.util.concurrent.ConcurrentHashMap<String, CachedSnapshot> snapshotCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long SNAPSHOT_TTL_MILLIS = 3_000L;

    private record CachedSnapshot(int physicalStock, MarketSnapshot snapshot, long expiresAtMillis) {}

    public StatisticalPriceDiscoveryService(ItemMarketRepository repository, PluginSettings settings) {
        this.repository = repository;
        updateSettings(settings);
    }

    public void updateSettings(PluginSettings settings) {
        this.pricing = settings.pricing();
        // 冷启动锚点/初始方差下发给仓储层，供新物品建档使用。
        repository.setColdStartParams(pricing.anchorPrice(), pricing.initialVariance());
        snapshotCache.clear();
    }

    // ---------------------------------------------------------------------
    // 只读快照 / 状态
    // ---------------------------------------------------------------------

    public MarketSnapshot snapshot(ItemMarketRecord record) {
        long now = System.currentTimeMillis();
        CachedSnapshot cached = snapshotCache.get(record.getItemHash());
        if (cached != null && now < cached.expiresAtMillis() && cached.physicalStock() == record.getPhysicalStock()) {
            return cached.snapshot();
        }
        DiscoveryState state = repository.getOrCreateDiscoveryState(record.getItemHash());
        // 只读路径也做一次时间更新（不落库），让长时间无成交的物品价差如实变宽。
        DiscoveryState projected = timeUpdate(state, now);
        MarketSnapshot snapshot = toSnapshot(record, projected);
        snapshotCache.put(record.getItemHash(), new CachedSnapshot(record.getPhysicalStock(), snapshot, now + SNAPSHOT_TTL_MILLIS));
        return snapshot;
    }

    public DiscoveryState currentState(ItemMarketRecord record) {
        return timeUpdate(repository.getOrCreateDiscoveryState(record.getItemHash()), System.currentTimeMillis());
    }

    public void invalidateSnapshot(String itemHash) {
        if (itemHash != null) {
            snapshotCache.remove(itemHash);
        }
    }

    // ---------------------------------------------------------------------
    // 卡尔曼更新（成交证据）
    // ---------------------------------------------------------------------

    /**
     * 纳入一笔成交作为对数公允价值的观测。执行：时间更新 → 鲁棒测量更新 → 波动/多样性 EWMA → 落库。
     */
    public void ingestTradeEvidence(ItemMarketRecord record,
                                    TradeType tradeType,
                                    int amount,
                                    double totalPrice,
                                    UUID playerUuid,
                                    long now) {
        DiscoveryState prior = repository.getOrCreateDiscoveryState(record.getItemHash());

        // 1) 时间更新：按 Δt 与交易者多样性放大不确定性。
        DiscoveryState projected = timeUpdate(prior, now);

        double observedUnitPrice = amount <= 0 ? 0.0D : (totalPrice / amount);
        if (!(observedUnitPrice > 0.0D) || !Double.isFinite(observedUnitPrice)) {
            // 无效观测：仅落库时间更新后的状态。
            repository.saveDiscoveryState(withTimestamps(projected, now));
            invalidateSnapshot(record.getItemHash());
            return;
        }
        double z = Math.log(observedUnitPrice);

        // 2) 鲁棒测量更新（Student-t IRLS 单步）。
        double volume = Math.max(1.0D, amount);
        double R = Math.max(1.0e-9D, pricing.observationNoiseBase() / volume);
        double innovation = z - projected.xLogValue();
        double S = projected.pVar() + R;
        double dof = Math.max(1.0D, pricing.studentTDof());
        double w = (dof + 1.0D) / (dof + (innovation * innovation) / Math.max(1.0e-12D, S));
        w = clamp(w, 0.0D, 1.0D);
        double robustR = R / Math.max(1.0e-6D, w);          // w→0（离群）→ robustR→∞ → 几乎不更新
        double K = projected.pVar() / (projected.pVar() + robustR);
        double newX = projected.xLogValue() + K * innovation;
        double newP = Math.max(1.0e-9D, (1.0D - K) * projected.pVar());

        // 3) 已实现波动 EWMA（用创新平方近似对数收益方差）。
        double volAlpha = ewmaAlpha(pricing.volatilityHalfLifeHours(), projected, now);
        double newVol = (1.0D - volAlpha) * projected.volEwma() + volAlpha * (innovation * innovation);

        // 4) 交易者多样性 EWMA（窗口内独立交易者数），用于下一次时间更新的过程噪声调节。
        int distinctTraders = repository.queryRecentDistinctTraders(
            record.getItemHash(), now - hoursToMillis(pricing.diversityWindowHours()));
        double newDiversity = 0.5D * projected.diversityEwma() + 0.5D * Math.max(1.0D, distinctTraders);

        DiscoveryState posterior = new DiscoveryState(
            projected.itemHash(), newX, newP, newVol, newDiversity, now, now);
        repository.saveDiscoveryState(posterior);
        invalidateSnapshot(record.getItemHash());
    }

    // ---------------------------------------------------------------------
    // 报价（Avellaneda-Stoikov）
    // ---------------------------------------------------------------------

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
        return new QuoteComputation(Math.max(0.0D, total), Math.max(1, record.getPhysicalStock() - amount), snapshot);
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
        return new QuoteComputation(Math.max(0.0D, total), Math.max(1, record.getPhysicalStock() + amount), snapshot);
    }

    /**
     * 边际价：沿库存变化对 A-S reservation ± 半价差 + 深度滑点积分。
     */
    private double marginalPrice(MarketSnapshot snapshot, double projectedPhysicalStock, int stepIndex, TradeType tradeType) {
        double targetFloat = Math.max(1.0D, snapshot.trustedFloat());
        double invImbalance = (targetFloat - projectedPhysicalStock) / targetFloat; // 正=超卖压力
        double sigma2 = snapshot.sigmaLogPrice() * snapshot.sigmaLogPrice();
        double skew = pricing.gamma() * pricing.inventoryRiskWeight() * invImbalance * sigma2;
        double reservation = Math.exp(Math.log(Math.max(1.0e-9D, snapshot.reservationPrice())) + skew - defaultSkew(snapshot));
        double depth = Math.max(1.0D, snapshot.liquidityDepth());
        double slippage = Math.exp((stepIndex / depth) * snapshot.halfSpread());
        return switch (tradeType) {
            case BUY -> reservation * Math.exp(snapshot.halfSpread()) * slippage;
            case SELL -> reservation * Math.exp(-snapshot.halfSpread()) / slippage;
        };
    }

    /**
     * snapshot.reservationPrice 已包含“当前库存”的偏移；marginalPrice 需要按 projected 库存重算偏移，
     * 因此先减去这个默认偏移再加上 projected 偏移。此处返回默认（当前库存）偏移量。
     */
    private double defaultSkew(MarketSnapshot snapshot) {
        double targetFloat = Math.max(1.0D, snapshot.trustedFloat());
        double invImbalance = (targetFloat - snapshot.currentPhysicalStockHint()) / targetFloat;
        double sigma2 = snapshot.sigmaLogPrice() * snapshot.sigmaLogPrice();
        return pricing.gamma() * pricing.inventoryRiskWeight() * invImbalance * sigma2;
    }

    // ---------------------------------------------------------------------
    // 卡尔曼时间更新 + 快照构造
    // ---------------------------------------------------------------------

    /**
     * 时间更新：按经过时间与交易者多样性放大后验方差。不修改 x（随机游走均值不变）。
     */
    private DiscoveryState timeUpdate(DiscoveryState state, long now) {
        long last = state.lastTradeAtMillis() > 0 ? state.lastTradeAtMillis() : state.updatedAtMillis();
        double hours = Math.max(0.0D, (now - last) / 3_600_000.0D);
        double diversity = Math.max(1.0D, state.diversityEwma());
        double addedVar = pricing.processNoisePerHour() * hours / diversity;
        double newP = state.pVar() + Math.max(0.0D, addedVar);
        return new DiscoveryState(
            state.itemHash(), state.xLogValue(), newP, state.volEwma(),
            state.diversityEwma(), state.lastTradeAtMillis(), state.updatedAtMillis());
    }

    private MarketSnapshot toSnapshot(ItemMarketRecord record, DiscoveryState state) {
        double midPrice = Math.exp(state.xLogValue());
        double sigma = Math.sqrt(Math.max(0.0D, state.pVar() + state.volEwma()));
        double sigma2 = sigma * sigma;

        double trustedFloat = clamp(
            Math.max(1.0D, pricing.trustedFloatBaseline()),
            1.0D, Math.max(1.0D, pricing.maxDepth()));
        double liquidityDepth = clamp(
            Math.max(pricing.minDepth(), pricing.trustedFloatBaseline()),
            pricing.minDepth(), pricing.maxDepth());

        // A-S reservation：库存失衡 + 金库紧张同时偏移。
        double physical = Math.max(0.0D, record.getPhysicalStock());
        double invImbalance = (trustedFloat - physical) / trustedFloat;
        double cashShortfall = treasuryShortfall(midPrice);
        double skew = pricing.gamma() * sigma2
            * (pricing.inventoryRiskWeight() * invImbalance - pricing.treasuryRiskWeight() * cashShortfall);
        double reservationPrice = Math.max(0.01D, midPrice * Math.exp(skew));

        double halfSpread = pricing.baseFee() + 0.5D * pricing.gamma() * sigma2;
        double bidPrice = Math.max(0.01D, reservationPrice * Math.exp(-halfSpread));
        double askPrice = Math.max(bidPrice, reservationPrice * Math.exp(halfSpread));

        DiscoveryStage stage = deriveStage(state.pVar());
        // manipulationScore 语义重映射为“定价异常度”≈ 由不确定性折算的 0~1 读数（仅供 GUI/诊断展示）。
        double anomalyReadout = clamp(sigma / 2.0D, 0.0D, 1.0D);

        return new MarketSnapshot(
            record.getItemHash(),
            stage,
            midPrice,
            reservationPrice,
            bidPrice,
            askPrice,
            halfSpread,
            sigma,
            anomalyReadout,
            0.0D,                       // trustedBuyQty7d: v5 不再维护，占位
            0.0D,                       // trustedSellQty7d: 同上
            0,                          // distinctBuyers7d
            0,                          // distinctSellers7d
            0.0D,                       // top2FlowShare24h
            0.0D,                       // reversalRate24h
            trustedFloat,
            liquidityDepth,
            (int) physical
        );
    }

    /**
     * 金库紧张度 ∈ [0,1)：金库越接近枯竭越大，用于压低 bid。以 midPrice 归一化避免服务器规模敏感。
     */
    private double treasuryShortfall(double midPrice) {
        double treasury = ItemMarketRepository.centsToMoney(repository.getTreasuryBalanceCents());
        double reference = Math.max(1.0D, midPrice) * 1000.0D; // 约“千件参考深度”的金库
        double ratio = treasury / reference;
        return clamp(1.0D - ratio, 0.0D, 0.95D);
    }

    /**
     * 阶段纯为 UI 标签：由后验不确定性映射，不再承担正确性职责。
     */
    private DiscoveryStage deriveStage(double pVar) {
        double sigma = Math.sqrt(Math.max(0.0D, pVar));
        if (sigma >= 1.2D) {
            return DiscoveryStage.UNKNOWN;
        }
        if (sigma >= 0.35D) {
            return DiscoveryStage.DISCOVERY;
        }
        return DiscoveryStage.MATURE;
    }

    private DiscoveryState withTimestamps(DiscoveryState state, long now) {
        return new DiscoveryState(state.itemHash(), state.xLogValue(), state.pVar(), state.volEwma(),
            state.diversityEwma(), state.lastTradeAtMillis(), now);
    }

    private double ewmaAlpha(double halfLifeHours, DiscoveryState prior, long now) {
        long last = prior.lastTradeAtMillis() > 0 ? prior.lastTradeAtMillis() : prior.updatedAtMillis();
        double hours = Math.max(0.0D, (now - last) / 3_600_000.0D);
        double hl = Math.max(1.0e-6D, halfLifeHours);
        // 距离上次成交越久，本次样本权重越大（等价于按时间衰减旧值）。
        double alpha = 1.0D - Math.pow(0.5D, hours / hl);
        return clamp(Math.max(alpha, 0.1D), 0.1D, 1.0D);
    }

    private static long hoursToMillis(int hours) {
        return Math.max(1L, hours) * 60L * 60L * 1000L;
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value)) {
            return min;
        }
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    public record QuoteComputation(double totalPrice, int compatibilityInventory, MarketSnapshot snapshot) {
    }
}
