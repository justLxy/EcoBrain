package com.ecobrain.plugin.persistence;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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
    private final File dbFile;
    private static final int SCHEMA_VERSION = 3;

    public DatabaseManager(JavaPlugin plugin) {
        this.plugin = plugin;
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Failed to create plugin data folder");
        }
        this.dbFile = new File(dataFolder, "ecobrain.db");
        this.jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();
    }

    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    /**
     * 初始化数据库表结构与关键索引。
     */
    public void initializeSchema() {
        ensureSchemaVersion();

        String createMetaSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_meta (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """;
        String createItemsSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_items (
                item_hash TEXT PRIMARY KEY,
                item_base64 TEXT NOT NULL,
                base_price REAL NOT NULL,
                k_factor REAL NOT NULL,
                target_inventory INTEGER NOT NULL,
                current_inventory INTEGER NOT NULL,
                physical_stock INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL DEFAULT 0
            )
            """;
        String createTreasurySql = """
            CREATE TABLE IF NOT EXISTS ecobrain_treasury (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                balance_cents INTEGER NOT NULL
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
        String createPlayerTxSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_player_transactions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                trade_type TEXT NOT NULL,
                item_hash TEXT NOT NULL,
                quantity INTEGER NOT NULL,
                money_amount REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;
        String createAiTuningEventSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_ai_tuning_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                item_hash TEXT NOT NULL,
                action TEXT NOT NULL,
                reason TEXT NOT NULL,
                old_base_price REAL NOT NULL,
                new_base_price REAL NOT NULL,
                old_k_factor REAL NOT NULL,
                new_k_factor REAL NOT NULL,
                reward REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;
        String createRewardClaimsSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_reward_claims (
                player_uuid TEXT NOT NULL,
                reward_id TEXT NOT NULL,
                claimed_at INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, reward_id)
            )
            """;
        String createSystemMoneyReclaimsSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_system_money_reclaims (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                player_uuid TEXT NOT NULL,
                player_name TEXT NOT NULL,
                amount REAL NOT NULL,
                created_at INTEGER NOT NULL
            )
            """;
        String indexInventorySql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_items_inventory ON ecobrain_items(current_inventory)";
        String indexPhysicalSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_items_physical ON ecobrain_items(physical_stock)";
        String indexTradeTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_time ON ecobrain_trade_stats(created_at)";
        String indexTradeItemTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_item_time ON ecobrain_trade_stats(item_hash, created_at)";
        String indexPlayerTxSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_player_tx ON ecobrain_player_transactions(player_uuid, created_at)";
        String indexAiTuningTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_ai_tuning_time ON ecobrain_ai_tuning_events(created_at)";
        String indexAiTuningItemSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_ai_tuning_item ON ecobrain_ai_tuning_events(item_hash, created_at)";
        String indexRewardClaimsSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_reward_claims_uuid ON ecobrain_reward_claims(player_uuid, claimed_at)";
        String indexSystemMoneyReclaimsSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_system_money_reclaims_uuid ON ecobrain_system_money_reclaims(player_uuid, created_at)";

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(createMetaSql);
            statement.execute(createItemsSql);
            statement.execute(createTreasurySql);
            statement.execute(createRiskSql);
            statement.execute(createTradeStatSql);
            statement.execute(createPlayerTxSql);
            statement.execute(createAiTuningEventSql);
            statement.execute(createRewardClaimsSql);
            statement.execute(createSystemMoneyReclaimsSql);
            statement.execute(indexInventorySql);
            statement.execute(indexPhysicalSql);
            statement.execute(indexTradeTimeSql);
            statement.execute(indexTradeItemTimeSql);
            statement.execute(indexPlayerTxSql);
            statement.execute(indexAiTuningTimeSql);
            statement.execute(indexAiTuningItemSql);
            statement.execute(indexRewardClaimsSql);
            statement.execute(indexSystemMoneyReclaimsSql);

            // Write schema version and ensure treasury row exists
            statement.executeUpdate("INSERT INTO ecobrain_meta(k, v) VALUES('schema_version', '" + SCHEMA_VERSION + "') ON CONFLICT(k) DO UPDATE SET v=excluded.v");
            statement.executeUpdate("INSERT INTO ecobrain_treasury(id, balance_cents) VALUES(1, 0) ON CONFLICT(id) DO NOTHING");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
            throw new IllegalStateException("Failed to initialize schema", e);
        }
    }

    /**
     * EcoBrain 3.0 is not compatible with older schemas.
     * If the existing db is not schema_version=3, we delete it and rebuild from scratch.
     */
    private void ensureSchemaVersion() {
        if (!dbFile.exists()) {
            return;
        }
        boolean ok = false;
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            // If meta table doesn't exist, this will throw and we will reset.
            try (ResultSet rs = statement.executeQuery("SELECT v FROM ecobrain_meta WHERE k='schema_version' LIMIT 1")) {
                if (rs.next()) {
                    String v = rs.getString(1);
                    ok = String.valueOf(SCHEMA_VERSION).equals(v);
                }
            }
        } catch (Exception ignored) {
            ok = false;
        }
        if (ok) {
            return;
        }

        plugin.getLogger().warning("[EcoBrain] Detected incompatible database schema. Resetting ecobrain.db for EcoBrain 3.0.");
        try {
            if (!dbFile.delete()) {
                // On Windows / locked file scenarios delete may fail; try rename as a fallback.
                File backup = new File(dbFile.getParentFile(), "ecobrain.db.incompatible.backup");
                if (backup.exists()) {
                    // best-effort cleanup
                    //noinspection ResultOfMethodCallIgnored
                    backup.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                dbFile.renameTo(backup);
            }
        } catch (SecurityException se) {
            throw new IllegalStateException("Failed to reset incompatible database file", se);
        }
    }
}
