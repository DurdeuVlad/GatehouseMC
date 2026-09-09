package com.gatehousemc.persistence.sqlite;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.PlayerIdentity;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * SQL operations for the identity_blocks table.
 */
final class BlockStore {
    private final TransactionHelper tx;

    BlockStore(TransactionHelper tx) {
        this.tx = tx;
    }

    boolean isBlocked(String normalizedUsername) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT 1 FROM identity_blocks WHERE normalized_name=?")) {
            statement.setString(1, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    boolean insertBlock(PlayerIdentity identity, UUID sourceRequestId, AdminPrincipal actor, String reason, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "INSERT INTO identity_blocks(normalized_name, source_request_id, blocked_at, blocked_by_provider, blocked_by_external_id, blocked_by_display_name, reason) " +
                        "VALUES (?,?,?,?,?,?,?) ON CONFLICT(normalized_name) DO UPDATE SET source_request_id=excluded.source_request_id, blocked_at=excluded.blocked_at, " +
                        "blocked_by_provider=excluded.blocked_by_provider, blocked_by_external_id=excluded.blocked_by_external_id, " +
                        "blocked_by_display_name=excluded.blocked_by_display_name, reason=excluded.reason")) {
            statement.setString(1, identity.normalizedUsername());
            statement.setString(2, sourceRequestId.toString());
            statement.setLong(3, SqliteWorkflowRepository.millis(now));
            statement.setString(4, actor.provider());
            statement.setString(5, actor.externalId());
            statement.setString(6, actor.displayName());
            statement.setString(7, reason);
            statement.executeUpdate();
            return true;
        }
    }

    boolean removeBlock(String normalizedUsername) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("DELETE FROM identity_blocks WHERE normalized_name=?")) {
            statement.setString(1, normalizedUsername);
            return statement.executeUpdate() == 1;
        }
    }
}
