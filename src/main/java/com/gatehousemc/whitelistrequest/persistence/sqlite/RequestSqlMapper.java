package com.gatehousemc.whitelistrequest.persistence.sqlite;

import com.gatehousemc.whitelistrequest.domain.AdminPrincipal;
import com.gatehousemc.whitelistrequest.domain.DecisionAction;
import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import com.gatehousemc.whitelistrequest.domain.RequestStatus;
import com.gatehousemc.whitelistrequest.domain.WhitelistRequest;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL operations for the whitelist_requests table.
 */
final class RequestSqlMapper {
    private final TransactionHelper tx;

    RequestSqlMapper(TransactionHelper tx) {
        this.tx = tx;
    }

    WhitelistRequest findById(UUID requestId) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT * FROM whitelist_requests WHERE id=?")) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readRequest(result) : null; }
        }
    }

    WhitelistRequest findActiveByName(String normalizedUsername) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT * FROM whitelist_requests WHERE normalized_name=? AND status IN ('PENDING','RESOLVING') LIMIT 1")) {
            statement.setString(1, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readRequest(result) : null; }
        }
    }

    List<WhitelistRequest> findByStatus(Optional<RequestStatus> status, int limit) throws SQLException {
        String sql = "SELECT * FROM whitelist_requests" + (status.isPresent() ? " WHERE status=?" : "") + " ORDER BY updated_at DESC LIMIT ?";
        try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
            int index = 1;
            if (status.isPresent()) statement.setString(index++, status.get().name());
            statement.setInt(index, Math.max(1, Math.min(limit, 500)));
            try (ResultSet result = statement.executeQuery()) {
                List<WhitelistRequest> requests = new java.util.ArrayList<>();
                while (result.next()) requests.add(readRequest(result));
                return requests;
            }
        }
    }

    WhitelistRequest latestByStatus(String normalizedUsername, RequestStatus status) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT * FROM whitelist_requests WHERE normalized_name=? AND status=? ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, status.name());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readRequest(result) : null; }
        }
    }

    List<WhitelistRequest> findResolvingApprovals() throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT * FROM whitelist_requests WHERE status='RESOLVING' AND resolving_action='APPROVE'")) {
            try (ResultSet result = statement.executeQuery()) {
                List<WhitelistRequest> requests = new java.util.ArrayList<>();
                while (result.next()) requests.add(readRequest(result));
                return requests;
            }
        }
    }

    List<WhitelistRequest> findResolvingUndos() throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT * FROM whitelist_requests WHERE status='RESOLVING' AND resolving_action='UNDO'")) {
            try (ResultSet result = statement.executeQuery()) {
                List<WhitelistRequest> requests = new java.util.ArrayList<>();
                while (result.next()) requests.add(readRequest(result));
                return requests;
            }
        }
    }

    void insert(WhitelistRequest request) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "INSERT INTO whitelist_requests(id, normalized_name, requested_name, requested_uuid, status, created_at, updated_at, first_attempt_at, last_attempt_at, attempt_count) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, request.id().toString());
            statement.setString(2, request.identity().normalizedUsername());
            statement.setString(3, request.identity().exactUsername());
            statement.setString(4, request.identity().offlineUuid().toString());
            statement.setString(5, request.status().name());
            statement.setLong(6, SqliteWorkflowRepository.millis(request.createdAt()));
            statement.setLong(7, SqliteWorkflowRepository.millis(request.updatedAt()));
            statement.setLong(8, SqliteWorkflowRepository.millis(request.firstAttemptAt()));
            statement.setLong(9, SqliteWorkflowRepository.millis(request.lastAttemptAt()));
            statement.setLong(10, request.attemptCount());
            statement.executeUpdate();
        }
    }

    void updateAttemptTimestamps(UUID requestId, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("UPDATE whitelist_requests SET last_attempt_at=?, updated_at=?, attempt_count=attempt_count+1 WHERE id=?")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setLong(2, SqliteWorkflowRepository.millis(now));
            statement.setString(3, requestId.toString());
            statement.executeUpdate();
        }
    }

    boolean claimApproval(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='RESOLVING', resolving_action='APPROVE', resolving_token=?, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, updated_at=? WHERE id=? AND status='PENDING'")) {
            statement.setString(1, token.toString());
            statement.setString(2, actor.provider());
            statement.setString(3, actor.externalId());
            statement.setString(4, actor.displayName());
            statement.setString(5, reason);
            statement.setLong(6, SqliteWorkflowRepository.millis(now));
            statement.setString(7, requestId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean resolveTerminal(UUID requestId, RequestStatus status, AdminPrincipal actor, String reason, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status=?, resolved_at=?, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, updated_at=? WHERE id=? AND status='PENDING'")) {
            statement.setString(1, status.name());
            statement.setLong(2, SqliteWorkflowRepository.millis(now));
            statement.setString(3, actor.provider());
            statement.setString(4, actor.externalId());
            statement.setString(5, actor.displayName());
            statement.setString(6, reason);
            statement.setLong(7, SqliteWorkflowRepository.millis(now));
            statement.setString(8, requestId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean finalizeApproval(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='APPROVED', resolved_at=?, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, resolving_action=NULL, resolving_token=NULL, updated_at=? WHERE id=? AND status='RESOLVING' AND resolving_token=?")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setString(2, actor.provider());
            statement.setString(3, actor.externalId());
            statement.setString(4, actor.displayName());
            statement.setString(5, reason);
            statement.setLong(6, SqliteWorkflowRepository.millis(now));
            statement.setString(7, requestId.toString());
            statement.setString(8, token.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean resetApproval(UUID requestId, UUID token, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='PENDING', resolving_action=NULL, resolving_token=NULL, resolved_by_provider=NULL, resolved_by_external_id=NULL, resolved_by_display_name=NULL, resolution_reason=NULL, updated_at=? WHERE id=? AND status='RESOLVING' AND resolving_token=?")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setString(2, requestId.toString());
            statement.setString(3, token.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean reopen(UUID requestId, AdminPrincipal actor, String reason, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='PENDING', resolved_at=NULL, resolved_by_provider=NULL, resolved_by_external_id=NULL, resolved_by_display_name=NULL, resolution_reason=NULL, resolving_action=NULL, resolving_token=NULL, updated_at=? " +
                        "WHERE id=? AND status IN ('APPROVED','DENIED','BLOCKED')")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setString(2, requestId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean claimUndo(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='RESOLVING', resolving_action='UNDO', resolving_token=?, resolved_at=NULL, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, updated_at=? " +
                        "WHERE id=? AND status='APPROVED'")) {
            statement.setString(1, token.toString());
            statement.setString(2, actor.provider());
            statement.setString(3, actor.externalId());
            statement.setString(4, actor.displayName());
            statement.setString(5, reason);
            statement.setLong(6, SqliteWorkflowRepository.millis(now));
            statement.setString(7, requestId.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean finalizeUndo(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='PENDING', resolved_at=NULL, resolved_by_provider=NULL, resolved_by_external_id=NULL, resolved_by_display_name=NULL, resolution_reason=NULL, resolving_action=NULL, resolving_token=NULL, updated_at=? " +
                        "WHERE id=? AND status='RESOLVING' AND resolving_action='UNDO' AND resolving_token=?")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setString(2, requestId.toString());
            statement.setString(3, token.toString());
            return statement.executeUpdate() == 1;
        }
    }

    boolean resetUndo(UUID requestId, UUID token, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "UPDATE whitelist_requests SET status='APPROVED', resolving_action=NULL, resolving_token=NULL, resolved_by_provider=NULL, resolved_by_external_id=NULL, resolved_by_display_name=NULL, resolution_reason=NULL, updated_at=? " +
                        "WHERE id=? AND status='RESOLVING' AND resolving_action='UNDO' AND resolving_token=?")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setString(2, requestId.toString());
            statement.setString(3, token.toString());
            return statement.executeUpdate() == 1;
        }
    }

    void savePublication(UUID requestId, com.gatehousemc.whitelistrequest.domain.PublicationRef publication, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "INSERT INTO request_publications(request_id,provider,external_container_id,external_message_id,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?) " +
                        "ON CONFLICT(request_id,provider) DO UPDATE SET external_container_id=excluded.external_container_id, external_message_id=excluded.external_message_id, state=excluded.state, updated_at=excluded.updated_at")) {
            statement.setString(1, requestId.toString());
            statement.setString(2, publication.provider());
            statement.setString(3, publication.containerId());
            statement.setString(4, publication.messageId());
            statement.setString(5, "ACTIVE");
            statement.setLong(6, SqliteWorkflowRepository.millis(now));
            statement.setLong(7, SqliteWorkflowRepository.millis(now));
            statement.executeUpdate();
        }
    }

    List<com.gatehousemc.whitelistrequest.domain.PublicationRef> publications(UUID requestId) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT provider, external_container_id, external_message_id FROM request_publications WHERE request_id=? AND state='ACTIVE'")) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<com.gatehousemc.whitelistrequest.domain.PublicationRef> publications = new java.util.ArrayList<>();
                while (result.next()) publications.add(new com.gatehousemc.whitelistrequest.domain.PublicationRef(result.getString(1), result.getString(2), result.getString(3)));
                return publications;
            }
        }
    }

    static WhitelistRequest readRequest(ResultSet result) throws SQLException {
        UUID id = UUID.fromString(result.getString("id"));
        String exact = result.getString("requested_name");
        PlayerIdentity identity = PlayerIdentity.of(UUID.fromString(result.getString("requested_uuid")), exact);
        RequestStatus status = RequestStatus.valueOf(result.getString("status"));
        AdminPrincipal actor = result.getString("resolved_by_provider") == null ? null : new AdminPrincipal(
                result.getString("resolved_by_provider"), result.getString("resolved_by_external_id"), result.getString("resolved_by_display_name"));
        String action = result.getString("resolving_action");
        String token = result.getString("resolving_token");
        return new WhitelistRequest(id, identity, status, SqliteWorkflowRepository.instant(result.getLong("created_at")), SqliteWorkflowRepository.instant(result.getLong("updated_at")),
                SqliteWorkflowRepository.instant(result.getLong("first_attempt_at")), SqliteWorkflowRepository.instant(result.getLong("last_attempt_at")), result.getLong("attempt_count"),
                nullableInstant(result, "resolved_at"), actor, result.getString("resolution_reason"),
                action == null ? null : DecisionAction.valueOf(action), token == null ? null : UUID.fromString(token));
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : SqliteWorkflowRepository.instant(value);
    }
}
