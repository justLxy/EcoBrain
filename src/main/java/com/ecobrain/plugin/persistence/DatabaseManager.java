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
    private static final int SCHEMA_VERSION = 5;

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
        Connection connection = DriverManager.getConnection(jdbcUrl);
        applyConnectionPragmas(connection);
        return connection;
    }

    /**
     * 每条连接都要设置的 PRAGMA：
     * - busy_timeout: 并发写时不再直接抛 SQLITE_BUSY，而是自旋等待，显著降低异步交易高峰的失败率
     * - synchronous=NORMAL: 配合 WAL 时安全，且省掉每次 commit 的 fsync，写入吞吐提升数倍
     * - foreign_keys=ON: 保持外键约束（建表里声明了 FK，默认 SQLite 是关闭的）
     * - temp_store=MEMORY: 临时表/排序走内存，加速 GROUP BY / ORDER BY
     */
    private void applyConnectionPragmas(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout = 5000");
            statement.execute("PRAGMA synchronous = NORMAL");
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    /**
     * 初始化数据库表结构与关键索引。
     */
    public void initializeSchema() {
        ensureSchemaVersion();
        enableWalMode();

        String createMetaSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_meta (
                k TEXT PRIMARY KEY,
                v TEXT NOT NULL
            )
            """;
        // v5: 遗留 vAMM 字段 (base_price/k_factor/target_inventory/current_inventory) 已移除。
        // 定价完全由 ecobrain_discovery_state 的卡尔曼状态驱动，物品只保留身份与真实库存。
        String createItemsSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_items (
                item_hash TEXT PRIMARY KEY,
                item_base64 TEXT NOT NULL,
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
        // v5: 卡尔曼滤波慢状态。
        // - x_log_value: 对数公允价值后验均值
        // - p_var: 其方差（不确定性，取代旧 sigma，且不再 clamp 下限）
        // - vol_ewma: 已实现对数收益方差的 EWMA（快速波动，喂给 A-S spread）
        // - diversity_ewma: 有效交易者多样性 EWMA（越低越怀疑单人刷价，过程噪声涨得越快）
        // - last_trade_at: 上一笔成交时间（用于时间更新的 Δt）
        String createDiscoveryStateSql = """
            CREATE TABLE IF NOT EXISTS ecobrain_discovery_state (
                item_hash TEXT PRIMARY KEY,
                x_log_value REAL NOT NULL,
                p_var REAL NOT NULL,
                vol_ewma REAL NOT NULL DEFAULT 0,
                diversity_ewma REAL NOT NULL DEFAULT 1,
                last_trade_at INTEGER NOT NULL DEFAULT 0,
                updated_at INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (item_hash) REFERENCES ecobrain_items(item_hash)
            )
            """;
        String indexPhysicalSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_items_physical ON ecobrain_items(physical_stock)";
        String indexTradeTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_time ON ecobrain_trade_stats(created_at)";
        String indexTradeItemTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_item_time ON ecobrain_trade_stats(item_hash, created_at)";
        String indexPlayerTxSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_player_tx ON ecobrain_player_transactions(player_uuid, created_at)";
        String indexPlayerTxItemTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_player_tx_item_time ON ecobrain_player_transactions(item_hash, created_at)";
        String indexRewardClaimsSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_reward_claims_uuid ON ecobrain_reward_claims(player_uuid, claimed_at)";
        String indexSystemMoneyReclaimsSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_system_money_reclaims_uuid ON ecobrain_system_money_reclaims(player_uuid, created_at)";
        String indexDiscoveryUpdatedSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_discovery_updated ON ecobrain_discovery_state(updated_at)";
        // v5: 卡尔曼滤波不再跑 discovery 窗口相关子查询，但排行榜/个人统计仍按 trade_type 聚合。
        String indexPlayerTxItemPlayerTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_player_tx_item_player_time ON ecobrain_player_transactions(item_hash, player_uuid, created_at)";
        // 排行榜与个人统计按 trade_type 聚合，加一个 (trade_type, player_uuid) 覆盖索引避免全表扫描。
        String indexPlayerTxTypePlayerSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_player_tx_type_player ON ecobrain_player_transactions(trade_type, player_uuid)";
        // trade_stats 频繁按 (item_hash, trade_type, created_at) 过滤（SELL 证据等）。
        String indexTradeItemTypeTimeSql = "CREATE INDEX IF NOT EXISTS idx_ecobrain_trade_item_type_time ON ecobrain_trade_stats(item_hash, trade_type, created_at)";

        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(createMetaSql);
            statement.execute(createItemsSql);
            statement.execute(createTreasurySql);
            statement.execute(createRiskSql);
            statement.execute(createTradeStatSql);
            statement.execute(createPlayerTxSql);
            statement.execute(createRewardClaimsSql);
            statement.execute(createSystemMoneyReclaimsSql);
            statement.execute(createDiscoveryStateSql);
            statement.execute(indexPhysicalSql);
            statement.execute(indexTradeTimeSql);
            statement.execute(indexTradeItemTimeSql);
            statement.execute(indexPlayerTxSql);
            statement.execute(indexPlayerTxItemTimeSql);
            statement.execute(indexRewardClaimsSql);
            statement.execute(indexSystemMoneyReclaimsSql);
            statement.execute(indexDiscoveryUpdatedSql);
            statement.execute(indexPlayerTxItemPlayerTimeSql);
            statement.execute(indexPlayerTxTypePlayerSql);
            statement.execute(indexTradeItemTypeTimeSql);

            // Write schema version and ensure treasury row exists
            statement.executeUpdate("INSERT INTO ecobrain_meta(k, v) VALUES('schema_version', '" + SCHEMA_VERSION + "') ON CONFLICT(k) DO UPDATE SET v=excluded.v");
            statement.executeUpdate("INSERT INTO ecobrain_treasury(id, balance_cents) VALUES(1, 0) ON CONFLICT(id) DO NOTHING");
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to initialize database schema: " + e.getMessage());
            throw new IllegalStateException("Failed to initialize schema", e);
        }
    }

    /**
     * 开启 WAL 日志模式（数据库级持久设置，只需在启动时执行一次）。
     * WAL 允许读写并发（读不再阻塞写），对“异步交易 + 排行榜/占位符并发读”场景收益巨大。
     */
    private void enableWalMode() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException e) {
            plugin.getLogger().warning("[EcoBrain] Failed to enable WAL journal mode: " + e.getMessage());
        }
    }

    /**
     * EcoBrain 3.x is not compatible with older schemas.
     * If the existing db is not the current schema version, we delete it and rebuild from scratch.
     */
    private void ensureSchemaVersion() {
        if (!dbFile.exists()) {
            return;
        }
        boolean ok = false;
        String existingVersion = null;
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            // If meta table doesn't exist, this will throw and we will reset.
            try (ResultSet rs = statement.executeQuery("SELECT v FROM ecobrain_meta WHERE k='schema_version' LIMIT 1")) {
                if (rs.next()) {
                    existingVersion = rs.getString(1);
                    ok = String.valueOf(SCHEMA_VERSION).equals(existingVersion);
                }
            }
        } catch (Exception ignored) {
            ok = false;
        }
        if (ok) {
            return;
        }
        if ("3".equals(existingVersion)) {
            migrateSchema3To4();
            existingVersion = "4";
        }
        if ("4".equals(existingVersion)) {
            migrateSchema4To5();
            return;
        }

        plugin.getLogger().warning("[EcoBrain] Detected incompatible database schema. Resetting ecobrain.db for EcoBrain 5.0.");
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

    private void migrateSchema3To4() {
        try (Connection connection = getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ecobrain_meta SET v='4' WHERE k='schema_version'");
            plugin.getLogger().info("[EcoBrain] Migrated database schema from v3 to v4 in place.");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to migrate schema from v3 to v4", e);
        }
    }

    /**
     * v4 -> v5 就地迁移（不清库，保留物品身份、真实库存与全部交易/排行榜历史）：
     * - ecobrain_items: 删除遗留 vAMM 列 (base_price/k_factor/target_inventory/current_inventory)，
     *   只保留 item_hash / item_base64 / physical_stock / created_at。
     * - ecobrain_discovery_state: 重建为卡尔曼慢状态，x_log_value 用旧 mu_log_price 播种，
     *   p_var 用旧 sigma_log_price^2（无历史则给较大的初始不确定性），其余置初值。
     * - ecobrain_ai_tuning_events: 直接丢弃（PPO 审计表已废弃）。
     *
     * 采用“建新表 -> 拷数据 -> 换名”的重建法，兼容不支持 DROP COLUMN 的旧 SQLite。
     */
    private void migrateSchema4To5() {
        try (Connection connection = getConnection()) {
            boolean prevAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // 1) items 瘦身
                statement.execute("""
                    CREATE TABLE ecobrain_items_v5 (
                        item_hash TEXT PRIMARY KEY,
                        item_base64 TEXT NOT NULL,
                        physical_stock INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
                statement.execute("""
                    INSERT INTO ecobrain_items_v5(item_hash, item_base64, physical_stock, created_at)
                    SELECT item_hash, item_base64, physical_stock, created_at FROM ecobrain_items
                    """);
                statement.execute("DROP TABLE ecobrain_items");
                statement.execute("ALTER TABLE ecobrain_items_v5 RENAME TO ecobrain_items");

                // 2) discovery_state 重建为卡尔曼状态，播种自旧后验
                statement.execute("""
                    CREATE TABLE ecobrain_discovery_state_v5 (
                        item_hash TEXT PRIMARY KEY,
                        x_log_value REAL NOT NULL,
                        p_var REAL NOT NULL,
                        vol_ewma REAL NOT NULL DEFAULT 0,
                        diversity_ewma REAL NOT NULL DEFAULT 1,
                        last_trade_at INTEGER NOT NULL DEFAULT 0,
                        updated_at INTEGER NOT NULL DEFAULT 0
                    )
                    """);
                statement.execute("""
                    INSERT INTO ecobrain_discovery_state_v5(item_hash, x_log_value, p_var, vol_ewma, diversity_ewma, last_trade_at, updated_at)
                    SELECT item_hash,
                           mu_log_price,
                           MAX(0.01, sigma_log_price * sigma_log_price),
                           0, 1, 0, updated_at
                    FROM ecobrain_discovery_state
                    """);
                statement.execute("DROP TABLE ecobrain_discovery_state");
                statement.execute("ALTER TABLE ecobrain_discovery_state_v5 RENAME TO ecobrain_discovery_state");

                // 3) 丢弃废弃的 PPO 审计表
                statement.execute("DROP TABLE IF EXISTS ecobrain_ai_tuning_events");

                statement.executeUpdate("UPDATE ecobrain_meta SET v='5' WHERE k='schema_version'");
                connection.commit();
                plugin.getLogger().info("[EcoBrain] Migrated database schema from v4 to v5 in place (Kalman pricing).");
            } catch (SQLException e) {
                try { connection.rollback(); } catch (SQLException ignored) {}
                throw e;
            } finally {
                try { connection.setAutoCommit(prevAutoCommit); } catch (SQLException ignored) {}
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to migrate schema from v4 to v5", e);
        }
    }
}
