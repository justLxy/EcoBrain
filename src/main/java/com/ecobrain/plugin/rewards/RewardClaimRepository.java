package com.ecobrain.plugin.rewards;

import com.ecobrain.plugin.persistence.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class RewardClaimRepository {
    private final DatabaseManager db;

    public RewardClaimRepository(DatabaseManager db) {
        this.db = db;
    }

    public Set<String> getClaimedRewardIds(UUID playerUuid) {
        String sql = "SELECT reward_id FROM ecobrain_reward_claims WHERE player_uuid = ?";
        Set<String> set = new HashSet<>();
        try (Connection connection = db.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String id = rs.getString("reward_id");
                    if (id != null && !id.isBlank()) {
                        set.add(id);
                    }
                }
            }
            return set;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query reward claims", e);
        }
    }

    /**
     * @return true 表示本次成功领取（首次插入）；false 表示已领取过
     */
    public boolean tryClaim(UUID playerUuid, String rewardId, long claimedAtMillis) {
        String sql = "INSERT OR IGNORE INTO ecobrain_reward_claims(player_uuid, reward_id, claimed_at) VALUES(?, ?, ?)";
        try (Connection connection = db.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, playerUuid.toString());
            statement.setString(2, rewardId);
            statement.setLong(3, claimedAtMillis);
            int rows = statement.executeUpdate();
            return rows > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to claim reward", e);
        }
    }
}

