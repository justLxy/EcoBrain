package com.ecobrain.plugin.persistence;

import com.ecobrain.plugin.model.DiscoveryStage;
import com.ecobrain.plugin.model.DiscoveryState;
import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.SystemMoneyOutstanding;
import com.ecobrain.plugin.model.TradeType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 物品市场仓储层。
 * 所有方法都只做数据访问，不处理 Bukkit 线程上下文，调用方必须保证在异步线程执行。
 */
public class ItemMarketRepository {
    private final DatabaseManager databaseManager;

    // 卡尔曼冷启动参数：新物品首次建档时的中性锚点与初始不确定性。
    // 由定价服务在启动/热更新时通过 setColdStartParams 下发（默认值仅作兜底）。
    private volatile double coldStartAnchorPrice = 100.0D;
    private volatile double coldStartInitialVariance = 5.0D;

    public ItemMarketRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public void setColdStartParams(double anchorPrice, double initialVariance) {
        this.coldStartAnchorPrice = Math.max(1.0e-6D, anchorPrice);
        this.coldStartInitialVariance = Math.max(1.0e-6D, initialVariance);
    }

    public static long moneyToCents(double amount) {
        if (!Double.isFinite(amount) || amount <= 0.0D) {
            return 0L;
        }
        // Use decimal rounding to avoid binary float surprises.
        BigDecimal bd = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP);
        return bd.movePointRight(2).longValueExact();
    }

    public static double centsToMoney(long cents) {
        return ((double) cents) / 100.0D;
    }

    public long getTreasuryBalanceCents() {
        String sql = "SELECT balance_cents FROM ecobrain_treasury WHERE id = 1";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            if (!rs.next()) {
                return 0L;
            }
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read treasury balance", e);
        }
    }

    public void initializeTreasuryIfNeeded(long initialBalanceCents) {
        long safeInitial = Math.max(0L, initialBalanceCents);
        String metaSelect = "SELECT v FROM ecobrain_meta WHERE k='treasury_initialized' LIMIT 1";
        String metaInsert = "INSERT INTO ecobrain_meta(k, v) VALUES('treasury_initialized', '1') ON CONFLICT(k) DO UPDATE SET v=excluded.v";
        String setBalance = "UPDATE ecobrain_treasury SET balance_cents = ? WHERE id = 1";
        try (Connection connection = databaseManager.getConnection()) {
            boolean prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement sel = connection.prepareStatement(metaSelect);
                 ResultSet rs = sel.executeQuery()) {
                boolean already = rs.next() && "1".equals(rs.getString(1));
                if (!already) {
                    try (PreparedStatement upd = connection.prepareStatement(setBalance)) {
                        upd.setLong(1, safeInitial);
                        upd.executeUpdate();
                    }
                    try (PreparedStatement ins = connection.prepareStatement(metaInsert)) {
                        ins.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize treasury", e);
        }
    }

    /**
     * Atomically reserve (debit) treasury for a SELL payout.
     * Returns true if enough funds existed and were reserved.
     */
    public boolean tryReserveTreasuryCents(long amountCents) {
        long amt = Math.max(0L, amountCents);
        if (amt <= 0L) {
            return true;
        }
        String sql = """
            UPDATE ecobrain_treasury
            SET balance_cents = balance_cents - ?
            WHERE id = 1 AND balance_cents >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amt);
            statement.setLong(2, amt);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reserve treasury", e);
        }
    }

    public void releaseTreasuryCents(long amountCents) {
        long amt = Math.max(0L, amountCents);
        if (amt <= 0L) {
            return;
        }
        String sql = "UPDATE ecobrain_treasury SET balance_cents = balance_cents + ? WHERE id = 1";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release treasury", e);
        }
    }

    public void creditTreasuryCents(long amountCents) {
        long amt = Math.max(0L, amountCents);
        if (amt <= 0L) {
            return;
        }
        String sql = "UPDATE ecobrain_treasury SET balance_cents = balance_cents + ? WHERE id = 1";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, amt);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to credit treasury", e);
        }
    }

    public Optional<ItemMarketRecord> findByHash(String itemHash) {
        String sql = """
            SELECT item_hash, item_base64, physical_stock, created_at
            FROM ecobrain_items WHERE item_hash = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapRecord(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find item by hash", e);
        }
    }

    public List<ItemMarketRecord> findAll() {
        String sql = """
            SELECT item_hash, item_base64, physical_stock, created_at
            FROM ecobrain_items
            ORDER BY item_hash ASC
            """;
        List<ItemMarketRecord> list = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                list.add(mapRecord(rs));
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find all items", e);
        }
    }

    public DiscoveryState getOrCreateDiscoveryState(String itemHash) {
        try (Connection connection = databaseManager.getConnection()) {
            initializeDiscoveryStateIfMissing(connection, itemHash);
            String sql = """
                SELECT item_hash, x_log_value, p_var, vol_ewma, diversity_ewma, last_trade_at, updated_at
                FROM ecobrain_discovery_state
                WHERE item_hash = ?
                """;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, itemHash);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("Discovery state missing after initialization: " + itemHash);
                    }
                    return mapDiscoveryState(rs);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load discovery state", e);
        }
    }

    public void saveDiscoveryState(DiscoveryState state) {
        String sql = """
            INSERT INTO ecobrain_discovery_state(
                item_hash, x_log_value, p_var, vol_ewma, diversity_ewma, last_trade_at, updated_at
            )
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(item_hash) DO UPDATE SET
                x_log_value = excluded.x_log_value,
                p_var = excluded.p_var,
                vol_ewma = excluded.vol_ewma,
                diversity_ewma = excluded.diversity_ewma,
                last_trade_at = excluded.last_trade_at,
                updated_at = excluded.updated_at
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, state.itemHash());
            statement.setDouble(2, state.xLogValue());
            statement.setDouble(3, state.pVar());
            statement.setDouble(4, state.volEwma());
            statement.setDouble(5, state.diversityEwma());
            statement.setLong(6, state.lastTradeAtMillis());
            statement.setLong(7, state.updatedAtMillis());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save discovery state", e);
        }
    }

    /**
     * 时间窗口内该物品的独立交易者数量。
     *
     * <p>用于卡尔曼滤波的“交易者多样性”过程噪声调节：流量越集中在少数人，
     * 越可能是自买自卖刷价，滤波越不敢收敛。取代了旧的 top2-share / reversal-rate /
     * distinct-buyers 一族昂贵的相关子查询与反操纵规则。</p>
     */
    public int queryRecentDistinctTraders(String itemHash, long sinceMillis) {
        String sql = """
            SELECT COALESCE(COUNT(DISTINCT player_uuid), 0) AS traders
            FROM ecobrain_player_transactions
            WHERE item_hash = ? AND created_at >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("traders") : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query recent distinct traders", e);
        }
    }

    /**
     * IPO 冷启动写入。若已存在则保持原记录不变。
     *
     * <p>v5: 物品只记录身份与真实库存；定价慢状态由 discovery_state 卡尔曼滤波承载，
     * 其冷启动锚点/初始方差由 {@link #setColdStartParams} 下发。</p>
     */
    public boolean upsertIpo(String itemHash, String itemBase64, int physicalStock) {
        String sql = """
            INSERT INTO ecobrain_items(item_hash, item_base64, physical_stock, created_at)
            VALUES(?, ?, ?, ?)
            ON CONFLICT(item_hash) DO NOTHING
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setString(2, itemBase64);
            statement.setInt(3, Math.max(0, physicalStock));
            statement.setLong(4, System.currentTimeMillis());
            int rows = statement.executeUpdate();
            initializeRiskIfMissing(itemHash, coldStartAnchorPrice, connection);
            initializeDiscoveryStateIfMissing(connection, itemHash);
            return rows > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert IPO record", e);
        }
    }

    /**
     * 更新真实库存。v5 起物品不再持有虚拟库存池，卖出/买入结算都只改 physical_stock。
     */
    public void updatePhysicalStockOnly(String itemHash, int newPhysicalStock) {
        String sql = "UPDATE ecobrain_items SET physical_stock = ? WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(0, newPhysicalStock));
            statement.setString(2, itemHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update physical stock", e);
        }
    }

    /**
     * 买入的“库存预留”：
     * 先在异步线程里原子扣减 physical_stock，避免并发买入导致超卖/负库存。
     *
     * @return true 表示预留成功；false 表示库存不足或会跌破熔断线
     */
    public boolean tryReservePhysicalStockForBuy(String itemHash, int amount, int criticalInventory) {
        String sql = """
            UPDATE ecobrain_items
            SET physical_stock = physical_stock - ?
            WHERE item_hash = ?
              AND (physical_stock - ?) >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(0, amount));
            statement.setString(2, itemHash);
            statement.setInt(3, Math.max(0, amount));
            statement.setInt(4, Math.max(0, criticalInventory));
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reserve physical stock", e);
        }
    }

    /**
     * 预留失败/取消购买时，归还已预留的 physical_stock。
     */
    public void releaseReservedPhysicalStock(String itemHash, int amount) {
        String sql = "UPDATE ecobrain_items SET physical_stock = physical_stock + ? WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(0, amount));
            statement.setString(2, itemHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to release reserved physical stock", e);
        }
    }

    public void deleteByHash(String itemHash) {
        String deleteStatsSql = "DELETE FROM ecobrain_trade_stats WHERE item_hash = ?";
        String deleteRiskSql = "DELETE FROM ecobrain_risk WHERE item_hash = ?";
        String deleteDiscoverySql = "DELETE FROM ecobrain_discovery_state WHERE item_hash = ?";
        String deleteItemSql = "DELETE FROM ecobrain_items WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection()) {
            try (PreparedStatement stats = connection.prepareStatement(deleteStatsSql);
                 PreparedStatement risk = connection.prepareStatement(deleteRiskSql);
                 PreparedStatement discovery = connection.prepareStatement(deleteDiscoverySql);
                 PreparedStatement item = connection.prepareStatement(deleteItemSql)) {
                stats.setString(1, itemHash);
                risk.setString(1, itemHash);
                discovery.setString(1, itemHash);
                item.setString(1, itemHash);
                stats.executeUpdate();
                risk.executeUpdate();
                discovery.executeUpdate();
                item.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete hash", e);
        }
    }

    public int deleteAllByPhysicalStock(int physicalStock) {
        String selectSql = "SELECT item_hash FROM ecobrain_items WHERE physical_stock = ?";
        String deleteStatsSql = "DELETE FROM ecobrain_trade_stats WHERE item_hash = ?";
        String deleteRiskSql = "DELETE FROM ecobrain_risk WHERE item_hash = ?";
        String deleteDiscoverySql = "DELETE FROM ecobrain_discovery_state WHERE item_hash = ?";
        String deleteItemSql = "DELETE FROM ecobrain_items WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection()) {
            boolean prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                List<String> hashes = new ArrayList<>();
                try (PreparedStatement select = connection.prepareStatement(selectSql)) {
                    select.setInt(1, physicalStock);
                    try (ResultSet rs = select.executeQuery()) {
                        while (rs.next()) {
                            hashes.add(rs.getString("item_hash"));
                        }
                    }
                }
                if (hashes.isEmpty()) {
                    connection.commit();
                    return 0;
                }

                try (PreparedStatement stats = connection.prepareStatement(deleteStatsSql);
                     PreparedStatement risk = connection.prepareStatement(deleteRiskSql);
                     PreparedStatement discovery = connection.prepareStatement(deleteDiscoverySql);
                     PreparedStatement item = connection.prepareStatement(deleteItemSql)) {
                    for (String hash : hashes) {
                        stats.setString(1, hash);
                        stats.addBatch();
                        risk.setString(1, hash);
                        risk.addBatch();
                        discovery.setString(1, hash);
                        discovery.addBatch();
                        item.setString(1, hash);
                        item.addBatch();
                    }
                    stats.executeBatch();
                    risk.executeBatch();
                    discovery.executeBatch();
                    item.executeBatch();
                }

                connection.commit();
                return hashes.size();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                }
                throw e;
            } finally {
                try {
                    connection.setAutoCommit(prevAutoCommit);
                } catch (SQLException ignored) {
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete items by physical stock", e);
        }
    }

    public void recordTrade(String itemHash, TradeType tradeType, int quantity, double totalPrice, long createdAtMillis) {
        String sql = """
            INSERT INTO ecobrain_trade_stats(item_hash, trade_type, quantity, total_price, created_at)
            VALUES(?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setString(2, tradeType.name());
            statement.setInt(3, quantity);
            statement.setDouble(4, totalPrice);
            statement.setLong(5, createdAtMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record trade", e);
        }
    }

    public void recordPlayerTransaction(java.util.UUID playerUuid, String playerName, TradeType tradeType, String itemHash, int quantity, double moneyAmount, long createdAtMillis) {
        String sql = """
            INSERT INTO ecobrain_player_transactions(player_uuid, player_name, trade_type, item_hash, quantity, money_amount, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName);
            statement.setString(3, tradeType.name());
            statement.setString(4, itemHash);
            statement.setInt(5, quantity);
            statement.setDouble(6, moneyAmount);
            statement.setLong(7, createdAtMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record player transaction", e);
        }
    }

    /**
     * 清空交易排行榜数据源（玩家交易统计表）。
     * 仅影响排行榜展示，不影响市场物品库存/价格等核心状态。
     *
     * @return 被删除的行数（SQLite 可能返回 0 表示未知）
     */
    public int clearLeaderboard() {
        String sql = "DELETE FROM ecobrain_player_transactions";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear leaderboard", e);
        }
    }

    /**
     * 判断一段时间内是否发生过“卖给系统”(SELL) 事件，用作供给证据。
     */
    public boolean hasSellSince(String itemHash, long sinceMillis) {
        String sql = """
            SELECT 1
            FROM ecobrain_trade_stats
            WHERE item_hash = ? AND trade_type = 'SELL' AND created_at >= ?
            LIMIT 1
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query sell evidence", e);
        }
    }

    public List<com.ecobrain.plugin.model.PlayerStat> getTopPlayers(TradeType tradeType, int limit) {
        String sql = """
            SELECT player_name, SUM(money_amount) as total_money
            FROM ecobrain_player_transactions
            WHERE trade_type = ?
            GROUP BY player_uuid, player_name
            ORDER BY total_money DESC
            LIMIT ?
            """;
        List<com.ecobrain.plugin.model.PlayerStat> stats = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tradeType.name());
            statement.setInt(2, limit);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    stats.add(new com.ecobrain.plugin.model.PlayerStat(
                        rs.getString("player_name"),
                        rs.getDouble("total_money")
                    ));
                }
            }
            return stats;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query top players", e);
        }
    }

    public double getPlayerTotalMoney(UUID playerUuid, TradeType tradeType) {
        String sql = """
            SELECT COALESCE(SUM(money_amount), 0) AS total_money
            FROM ecobrain_player_transactions
            WHERE player_uuid = ? AND trade_type = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, tradeType.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("total_money") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query player total money", e);
        }
    }

    public long getPlayerTotalQuantity(UUID playerUuid, TradeType tradeType) {
        String sql = """
            SELECT COALESCE(SUM(quantity), 0) AS total_qty
            FROM ecobrain_player_transactions
            WHERE player_uuid = ? AND trade_type = ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, tradeType.name());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("total_qty") : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query player total quantity", e);
        }
    }

    /**
     * 玩家在指定交易类型下的“成交额榜位”（按总额降序）。
     * 并列同额使用相同名次：rank = 1 + 领先于该玩家的“不同玩家数”。
     *
     * @return 0 表示该玩家没有任何记录
     */
    public int getPlayerRank(UUID playerUuid, TradeType tradeType) {
        String sql = """
            WITH totals AS (
                SELECT player_uuid, SUM(money_amount) AS total_money
                FROM ecobrain_player_transactions
                WHERE trade_type = ?
                GROUP BY player_uuid
            ),
            me AS (
                SELECT total_money AS me_total
                FROM totals
                WHERE player_uuid = ?
            )
            SELECT
                CASE
                    WHEN (SELECT me_total FROM me) IS NULL THEN 0
                    ELSE 1 + (SELECT COUNT(*) FROM totals WHERE total_money > (SELECT me_total FROM me))
                END AS rank
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tradeType.name());
            statement.setString(2, playerUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt("rank") : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query player rank", e);
        }
    }

    /**
     * 查询所有玩家“系统资金净额（未回收部分）”：
     * outstanding = SUM(SELL) - SUM(BUY) - SUM(system_money_reclaims)
     *
     * 说明：
     * - SELL: 系统发钱给玩家（玩家卖给系统）
     * - BUY: 系统从玩家收钱（玩家从系统购买）
     * - reclaims: 管理员通过命令执行的“系统资金回收”
     */
    public List<SystemMoneyOutstanding> getOutstandingSystemMoneyByPlayer(double minOutstanding) {
        double threshold = Math.max(0.0D, minOutstanding);
        String sql = """
            WITH tx AS (
                SELECT
                    player_uuid,
                    MAX(player_name) AS player_name,
                    COALESCE(SUM(
                        CASE
                            WHEN trade_type = 'SELL' THEN money_amount
                            WHEN trade_type = 'BUY' THEN -money_amount
                            ELSE 0
                        END
                    ), 0) AS net_money
                FROM ecobrain_player_transactions
                GROUP BY player_uuid
            ),
            reclaimed AS (
                SELECT player_uuid, COALESCE(SUM(amount), 0) AS reclaimed_money
                FROM ecobrain_system_money_reclaims
                GROUP BY player_uuid
            )
            SELECT
                tx.player_uuid AS player_uuid,
                tx.player_name AS player_name,
                (tx.net_money - COALESCE(reclaimed.reclaimed_money, 0)) AS outstanding_money
            FROM tx
            LEFT JOIN reclaimed ON reclaimed.player_uuid = tx.player_uuid
            WHERE (tx.net_money - COALESCE(reclaimed.reclaimed_money, 0)) > ?
            ORDER BY outstanding_money DESC
            """;
        List<SystemMoneyOutstanding> list = new ArrayList<>();
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDouble(1, threshold);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String uuidText = rs.getString("player_uuid");
                    String name = rs.getString("player_name");
                    double outstanding = rs.getDouble("outstanding_money");
                    if (uuidText == null || uuidText.isBlank()) {
                        continue;
                    }
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(uuidText);
                    } catch (IllegalArgumentException ignored) {
                        continue;
                    }
                    list.add(new SystemMoneyOutstanding(uuid, name == null ? "" : name, outstanding));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query outstanding system money", e);
        }
    }

    public void recordSystemMoneyReclaim(UUID playerUuid, String playerName, double amount, long createdAtMillis) {
        String sql = """
            INSERT INTO ecobrain_system_money_reclaims(player_uuid, player_name, amount, created_at)
            VALUES(?, ?, ?, ?)
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, playerName == null ? "" : playerName);
            statement.setDouble(3, amount);
            statement.setLong(4, createdAtMillis);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record system money reclaim", e);
        }
    }

    public long queryLastTradeTime(String itemHash) {
        String sql = "SELECT MAX(created_at) AS last_time FROM ecobrain_trade_stats WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong("last_time") : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query last trade time", e);
        }
    }

    public double queryVolumeSince(long sinceMillis) {
        String sql = "SELECT COALESCE(SUM(total_price),0) AS volume FROM ecobrain_trade_stats WHERE created_at >= ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("volume") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query volume", e);
        }
    }

    /**
     * 查询时间窗口内的平均订单总价 (Dynamic AOV)
     */
    public double queryDynamicAovSince(long sinceMillis) {
        String sql = """
            SELECT COALESCE(SUM(total_price) / NULLIF(COUNT(*), 0), 0) AS aov
            FROM ecobrain_trade_stats
            WHERE created_at >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("aov") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query dynamic AOV", e);
        }
    }

    /**
     * 查询时间窗口内的净印发金币总额 (SELL: 印发, BUY: 回收)
     */
    public double queryNetEmissionSince(long sinceMillis) {
        String sql = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN trade_type = 'SELL' THEN total_price
                    WHEN trade_type = 'BUY' THEN -total_price
                    ELSE 0
                END
            ), 0) AS net_emission
            FROM ecobrain_trade_stats
            WHERE created_at >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("net_emission") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query net emission", e);
        }
    }

    /**
     * 查询单个物品在时间窗口内的成交额。
     */
    public double queryItemVolumeSince(String itemHash, long sinceMillis) {
        String sql = """
            SELECT COALESCE(SUM(total_price),0) AS volume
            FROM ecobrain_trade_stats
            WHERE item_hash = ? AND created_at >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("volume") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query item volume", e);
        }
    }

    /**
     * 查询单个物品在时间窗口内的净流速：
     * BUY 记为正，SELL 记为负，结果越大表示买压越强。
     */
    public double queryItemNetFlowSince(String itemHash, long sinceMillis) {
        String sql = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN trade_type = 'BUY' THEN quantity
                    WHEN trade_type = 'SELL' THEN -quantity
                    ELSE 0
                END
            ),0) AS net_flow
            FROM ecobrain_trade_stats
            WHERE item_hash = ? AND created_at >= ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setLong(2, sinceMillis);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("net_flow") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query item net flow", e);
        }
    }

    /**
     * 查询单个物品在时间区间内的净流速（Between 版本）：
     * BUY 记为正，SELL 记为负。
     */
    public double queryItemNetFlowBetween(String itemHash, long fromMillisInclusive, long toMillisExclusive) {
        String sql = """
            SELECT COALESCE(SUM(
                CASE
                    WHEN trade_type = 'BUY' THEN quantity
                    WHEN trade_type = 'SELL' THEN -quantity
                    ELSE 0
                END
            ),0) AS net_flow
            FROM ecobrain_trade_stats
            WHERE item_hash = ? AND created_at >= ? AND created_at < ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setLong(2, fromMillisInclusive);
            statement.setLong(3, toMillisExclusive);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("net_flow") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query item net flow between", e);
        }
    }

    /**
     * 查询单个物品在时间区间内的单位均价（VWAP, volume-weighted avg unit price）：
     * unit_price = SUM(total_price) / SUM(quantity)
     */
    public double queryItemAvgUnitPriceBetween(String itemHash, long fromMillisInclusive, long toMillisExclusive) {
        String sql = """
            SELECT COALESCE(SUM(total_price) / NULLIF(SUM(quantity), 0), 0) AS unit_price
            FROM ecobrain_trade_stats
            WHERE item_hash = ? AND created_at >= ? AND created_at < ?
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setLong(2, fromMillisInclusive);
            statement.setLong(3, toMillisExclusive);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("unit_price") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query item avg unit price between", e);
        }
    }

    /**
     * 查询单个物品的“近似 TWAP”（time-weighted average price）：
     * - 将交易按时间桶聚合，每桶算一次单位均价（VWAP）
     * - 再对所有非空桶的均价做算术平均（每个时间桶等权），近似 time-weighted
     *
     * 说明：SQLite 不适合做复杂窗口函数；此实现对性能友好且能显著优于“TWAP=当前价”的退化版本。
     *
     * @param bucketMillis 时间桶大小（例如 5min/15min）
     */
    public double queryItemTwapSince(String itemHash, long sinceMillisInclusive, long bucketMillis) {
        long safeBucket = Math.max(60_000L, bucketMillis); // 至少 1 分钟，避免除 0/过细分桶
        String sql = """
            SELECT COALESCE(AVG(bucket_price), 0) AS twap
            FROM (
                SELECT
                    (created_at / ?) AS bucket,
                    (SUM(total_price) / NULLIF(SUM(quantity), 0)) AS bucket_price
                FROM ecobrain_trade_stats
                WHERE item_hash = ? AND created_at >= ?
                GROUP BY bucket
            ) t
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, safeBucket);
            statement.setString(2, itemHash);
            statement.setLong(3, sinceMillisInclusive);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getDouble("twap") : 0.0D;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query item TWAP since", e);
        }
    }

    public boolean isFrozen(String itemHash) {
        String sql = "SELECT is_frozen FROM ecobrain_risk WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return false;
                }
                return rs.getInt("is_frozen") == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query freeze flag", e);
        }
    }

    public void setFrozen(String itemHash, boolean frozen) {
        String sql = """
            INSERT INTO ecobrain_risk(item_hash, day_open_price, day_key, is_frozen)
            VALUES(?, 1.0, ?, ?)
            ON CONFLICT(item_hash) DO UPDATE SET is_frozen = excluded.is_frozen
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setString(2, LocalDate.now().toString());
            statement.setInt(3, frozen ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set frozen", e);
        }
    }

    /**
     * 批量设置所有已存在风险记录的冻结状态。
     * 注意：ecobrain_risk 表中不存在的 item_hash 视为未冻结，因此“全体解冻”只需更新已有行即可。
     *
     * @return 被更新的行数（SQLite 可能返回 0 表示未知）
     */
    public int setAllFrozen(boolean frozen) {
        String sql = "UPDATE ecobrain_risk SET is_frozen = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, frozen ? 1 : 0);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to set all frozen", e);
        }
    }

    /**
     * 更新或读取当天开盘价，用于熔断判断。
     */
    public double upsertAndGetDayOpenPrice(String itemHash, double currentPrice, String dayKey) {
        String querySql = "SELECT day_open_price, day_key FROM ecobrain_risk WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection()) {
            try (PreparedStatement query = connection.prepareStatement(querySql)) {
                query.setString(1, itemHash);
                try (ResultSet rs = query.executeQuery()) {
                    if (!rs.next()) {
                        insertRisk(connection, itemHash, currentPrice, dayKey, 0);
                        return currentPrice;
                    }
                    String savedDay = rs.getString("day_key");
                    double openPrice = rs.getDouble("day_open_price");
                    if (!dayKey.equals(savedDay)) {
                        String resetSql = "UPDATE ecobrain_risk SET day_open_price = ?, day_key = ?, is_frozen = 0 WHERE item_hash = ?";
                        try (PreparedStatement reset = connection.prepareStatement(resetSql)) {
                            reset.setDouble(1, currentPrice);
                            reset.setString(2, dayKey);
                            reset.setString(3, itemHash);
                            reset.executeUpdate();
                        }
                        return currentPrice;
                    }
                    return openPrice;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to upsert day open price", e);
        }
    }

    private void initializeRiskIfMissing(String itemHash, double basePrice, Connection connection) throws SQLException {
        String sql = """
            INSERT INTO ecobrain_risk(item_hash, day_open_price, day_key, is_frozen)
            VALUES(?, ?, ?, 0)
            ON CONFLICT(item_hash) DO NOTHING
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setDouble(2, basePrice);
            statement.setString(3, LocalDate.now().toString());
            statement.executeUpdate();
        }
    }

    private void insertRisk(Connection connection, String itemHash, double currentPrice, String dayKey, int frozen) throws SQLException {
        String sql = """
            INSERT INTO ecobrain_risk(item_hash, day_open_price, day_key, is_frozen)
            VALUES(?, ?, ?, ?)
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setDouble(2, currentPrice);
            statement.setString(3, dayKey);
            statement.setInt(4, frozen);
            statement.executeUpdate();
        }
    }

    private void initializeDiscoveryStateIfMissing(Connection connection, String itemHash) throws SQLException {
        DiscoveryState initial = DiscoveryState.initial(itemHash, coldStartAnchorPrice, coldStartInitialVariance);
        String sql = """
            INSERT INTO ecobrain_discovery_state(
                item_hash, x_log_value, p_var, vol_ewma, diversity_ewma, last_trade_at, updated_at
            )
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(item_hash) DO NOTHING
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, initial.itemHash());
            statement.setDouble(2, initial.xLogValue());
            statement.setDouble(3, initial.pVar());
            statement.setDouble(4, initial.volEwma());
            statement.setDouble(5, initial.diversityEwma());
            statement.setLong(6, initial.lastTradeAtMillis());
            statement.setLong(7, initial.updatedAtMillis());
            statement.executeUpdate();
        }
    }

    private DiscoveryState mapDiscoveryState(ResultSet rs) throws SQLException {
        return new DiscoveryState(
            rs.getString("item_hash"),
            rs.getDouble("x_log_value"),
            rs.getDouble("p_var"),
            rs.getDouble("vol_ewma"),
            rs.getDouble("diversity_ewma"),
            rs.getLong("last_trade_at"),
            rs.getLong("updated_at")
        );
    }

    private ItemMarketRecord mapRecord(ResultSet rs) throws SQLException {
        return new ItemMarketRecord(
            rs.getString("item_hash"),
            rs.getString("item_base64"),
            rs.getInt("physical_stock"),
            rs.getLong("created_at")
        );
    }

}
