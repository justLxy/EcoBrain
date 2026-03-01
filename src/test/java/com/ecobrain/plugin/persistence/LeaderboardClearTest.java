package com.ecobrain.plugin.persistence;

import com.ecobrain.plugin.model.TradeType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.util.UUID;

class LeaderboardClearTest {
    @Test
    void shouldClearLeaderboardTable() throws Exception {
        JavaPlugin plugin = Mockito.mock(JavaPlugin.class);
        var tempDir = Files.createTempDirectory("ecobrain-test-db-");
        Mockito.when(plugin.getDataFolder()).thenReturn(tempDir.toFile());

        DatabaseManager db = new DatabaseManager(plugin);
        db.initializeSchema();
        ItemMarketRepository repo = new ItemMarketRepository(db);

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        long now = System.currentTimeMillis();

        repo.recordPlayerTransaction(u1, "Alice", TradeType.SELL, "hash-a", 10, 123.0D, now);
        repo.recordPlayerTransaction(u2, "Bob", TradeType.BUY, "hash-b", 5, 77.0D, now);

        Assertions.assertFalse(repo.getTopPlayers(TradeType.SELL, 10).isEmpty());
        Assertions.assertFalse(repo.getTopPlayers(TradeType.BUY, 10).isEmpty());

        repo.clearLeaderboard();

        Assertions.assertTrue(repo.getTopPlayers(TradeType.SELL, 10).isEmpty());
        Assertions.assertTrue(repo.getTopPlayers(TradeType.BUY, 10).isEmpty());
    }
}

