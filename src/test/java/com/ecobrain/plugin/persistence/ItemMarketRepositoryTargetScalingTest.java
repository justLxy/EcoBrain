package com.ecobrain.plugin.persistence;

import com.ecobrain.plugin.model.ItemMarketRecord;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;

class ItemMarketRepositoryTargetScalingTest {

    @Test
    void shouldScaleCurrentInventoryProportionallyAndKeepPhysicalStock() throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        var tempDir = Files.createTempDirectory("ecobrain-test-db-");
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DatabaseManager db = new DatabaseManager(plugin);
        db.initializeSchema();
        ItemMarketRepository repo = new ItemMarketRepository(db);

        String hash = "hash-1";
        repo.upsertIpo(hash, "base64", 100.0D, 1.0D, 100, 50, 7);

        ItemMarketRecord before = repo.findByHash(hash).orElseThrow();
        Assertions.assertEquals(100, before.getTargetInventory());
        Assertions.assertEquals(50, before.getCurrentInventory());
        Assertions.assertEquals(7, before.getPhysicalStock());

        repo.updateTargetInventoryWithProportionalCurrentScaling(hash, 100, 50, 200);

        ItemMarketRecord after = repo.findByHash(hash).orElseThrow();
        Assertions.assertEquals(200, after.getTargetInventory());
        Assertions.assertEquals(100, after.getCurrentInventory()); // 50 * (200/100) = 100
        Assertions.assertEquals(7, after.getPhysicalStock()); // must remain unchanged
    }

    @Test
    void shouldRoundWhenRatioNotInteger() throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        var tempDir = Files.createTempDirectory("ecobrain-test-db-");
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DatabaseManager db = new DatabaseManager(plugin);
        db.initializeSchema();
        ItemMarketRepository repo = new ItemMarketRepository(db);

        String hash = "hash-2";
        repo.upsertIpo(hash, "base64", 100.0D, 1.0D, 192, 100, 0);

        repo.updateTargetInventoryWithProportionalCurrentScaling(hash, 192, 100, 193);

        ItemMarketRecord after = repo.findByHash(hash).orElseThrow();
        Assertions.assertEquals(193, after.getTargetInventory());
        // 100 * 193 / 192 = 100.5208.. -> round => 101
        Assertions.assertEquals(101, after.getCurrentInventory());
    }
}

