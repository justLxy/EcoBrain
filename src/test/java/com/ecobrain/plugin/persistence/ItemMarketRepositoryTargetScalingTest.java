package com.ecobrain.plugin.persistence;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;

/**
 * v5: 目标库存按比例缩放的概念已随遗留 vAMM 移除；保留对物品档案生命周期（按库存清理）的回归。
 */
class ItemMarketRepositoryTargetScalingTest {

    @Test
    void shouldDeleteOnlyItemsWithExactPhysicalStockOne() throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        var tempDir = Files.createTempDirectory("ecobrain-test-db-");
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DatabaseManager db = new DatabaseManager(plugin);
        db.initializeSchema();
        ItemMarketRepository repo = new ItemMarketRepository(db);

        repo.upsertIpo("hash-keep", "base64", 2);
        repo.upsertIpo("hash-delete", "base64", 1);

        int deleted = repo.deleteAllByPhysicalStock(1);

        Assertions.assertEquals(1, deleted);
        Assertions.assertTrue(repo.findByHash("hash-keep").isPresent());
        Assertions.assertTrue(repo.findByHash("hash-delete").isEmpty());
    }
}
