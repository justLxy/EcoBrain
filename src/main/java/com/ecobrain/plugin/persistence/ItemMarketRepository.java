package com.ecobrain.plugin.persistence;

import com.ecobrain.plugin.model.ItemMarketRecord;
import com.ecobrain.plugin.model.TradeType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 物品市场仓储层。
 * 所有方法都只做数据访问，不处理 Bukkit 线程上下文，调用方必须保证在异步线程执行。
 */
public class ItemMarketRepository {
    private final DatabaseManager databaseManager;

    public ItemMarketRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public Optional<ItemMarketRecord> findByHash(String itemHash) {
        String sql = """
            SELECT item_hash, item_base64, base_price, k_factor, target_inventory, current_inventory, physical_stock
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
            SELECT item_hash, item_base64, base_price, k_factor, target_inventory, current_inventory, physical_stock
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

    /**
     * IPO 冷启动写入。若已存在则保持原记录不变。
     */
    public void upsertIpo(String itemHash, String itemBase64, double basePrice, double kFactor,
                          int targetInventory, int currentInventory, int physicalStock) {
        String sql = """
            INSERT INTO ecobrain_items(item_hash, item_base64, base_price, k_factor, target_inventory, current_inventory, physical_stock)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(item_hash) DO NOTHING
            """;
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, itemHash);
            statement.setString(2, itemBase64);
            statement.setDouble(3, basePrice);
            statement.setDouble(4, kFactor);
            statement.setInt(5, targetInventory);
            statement.setInt(6, Math.max(1, currentInventory));
            statement.setInt(7, Math.max(0, physicalStock));
            statement.executeUpdate();
            initializeRiskIfMissing(itemHash, basePrice, connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert IPO record", e);
        }
    }

    public void updateStocks(String itemHash, int newVirtualInventory, int newPhysicalStock) {
        String sql = "UPDATE ecobrain_items SET current_inventory = ?, physical_stock = ? WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, Math.max(1, newVirtualInventory));
            statement.setInt(2, Math.max(0, newPhysicalStock));
            statement.setString(3, itemHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update stocks", e);
        }
    }

    public void updateTuning(String itemHash, double basePrice, double kFactor) {
        String sql = "UPDATE ecobrain_items SET base_price = ?, k_factor = ? WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDouble(1, basePrice);
            statement.setDouble(2, kFactor);
            statement.setString(3, itemHash);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update tuning", e);
        }
    }

    public void deleteByHash(String itemHash) {
        String deleteStatsSql = "DELETE FROM ecobrain_trade_stats WHERE item_hash = ?";
        String deleteRiskSql = "DELETE FROM ecobrain_risk WHERE item_hash = ?";
        String deleteItemSql = "DELETE FROM ecobrain_items WHERE item_hash = ?";
        try (Connection connection = databaseManager.getConnection()) {
            try (PreparedStatement stats = connection.prepareStatement(deleteStatsSql);
                 PreparedStatement risk = connection.prepareStatement(deleteRiskSql);
                 PreparedStatement item = connection.prepareStatement(deleteItemSql)) {
                stats.setString(1, itemHash);
                risk.setString(1, itemHash);
                item.setString(1, itemHash);
                stats.executeUpdate();
                risk.executeUpdate();
                item.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete hash", e);
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

    private ItemMarketRecord mapRecord(ResultSet rs) throws SQLException {
        return new ItemMarketRecord(
            rs.getString("item_hash"),
            rs.getString("item_base64"),
            rs.getDouble("base_price"),
            rs.getDouble("k_factor"),
            rs.getInt("target_inventory"),
            rs.getInt("current_inventory"),
            rs.getInt("physical_stock")
        );
    }
}
