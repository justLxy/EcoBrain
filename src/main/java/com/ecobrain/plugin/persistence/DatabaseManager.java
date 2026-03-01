package com.ecobrain.plugin.persistence;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite 连接与建表管理器。
 */
public class DatabaseManager {
    private final JavaPlugin plugin;
    private final String jdbcUrl;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create plugin data folder");
        }
        this.jdbcUrl = "jdbc:sqlite:" + new File(dataFolder, "ecobrain.db").getAbsolutePath();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * 初始化数据库表结构与关键索引。
     */
    public void initializeSchema() {
        String createItemsSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_items (
                item_hash TEXT PRIMARY KEY,
                item_base64 TEXT NOT NULL,
                base_price REAL NOT NULL,
                k_factor REAL NOT NULL,
                target_inventory INTEGER NOT NULL,
                current_inventory INTEGER NOT NULL,
                physical_stock INTEGER NOT NULL DEFAULT 0
            )
            """;
        String createRiskSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_risk (
                item_hash TEXT PRIMARY KEY,
                day_open_price REAL NOT NULL,
                day_key TEXT NOT NULL,
                is_frozen INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (item_hash) REFERENCES ecobrain_items(item_hash)
            )
            """;
        String createTradeStatSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_trade_stats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_hash TEXT NOT NULL,
                trade_type TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                total_price REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;
        String indexInventorySql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_items_inventory ON ecobrain_items(current_inventory)";
        String indexPhysicalSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_items_physical ON ecobrain_items(physical_stock)";
        String indexTradeTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_time ON ecobrain_trade_stats(created_at)";

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(createItemsSql);
            ensurePhysicalStockColumn(connection);
            statement.execute(createRiskSql);
            statement.execute(createTradeStatSql);
            statement.execute(indexInventorySql);
            statement.execute(indexPhysicalSql);
            statement.execute(indexTradeTimeSql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
            throw new IllegalStateException("Failed to initialize schema", e);
        }
    }

    /**
     * 兼容旧版本数据库：若历史表缺少 physical_stock，则在线补列。
     */
    private void ensurePhysicalStockColumn(Connection connection) throws SQLException {
        if (hasColumn(connection, "ecobrain_items", "physical_stock")) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ecobrain_items ADD COLUMN physical_stock INTEGER NOT NULL DEFAULT 0");
        }
    }

    private boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        String sql = "PRAGMA table_info(" + table + ")";
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("name");
                if (column.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }
    }
}
