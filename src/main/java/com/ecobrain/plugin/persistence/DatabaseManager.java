package com.ecobrain.plugin.persistence;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
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
                current_inventory INTEGER NOT NULL
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
        String indexTradeTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_time ON ecobrain_trade_stats(created_at)";

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(createItemsSql);
            statement.execute(createRiskSql);
            statement.execute(createTradeStatSql);
            statement.execute(indexInventorySql);
            statement.execute(indexTradeTimeSql);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
            throw new IllegalStateException("Failed to initialize schema", e);
        }
    }
}
