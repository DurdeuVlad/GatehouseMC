package com.gatehousemc.whitelistrequest.persistence.sqlite;

import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class SqliteWorkflowRepository implements WorkflowRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteWorkflowRepository.class);
    private final SqliteDatabase database;
    private final Connection connection;

    public SqliteWorkflowRepository(SqliteDatabase database) {
        this.database = database;
        this.connection = database.connection();
    }

    @Override
    public synchronized AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown) {
        return recordAttempt(identity, now, denialCooldown, 3);
    }

    private AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown,
                                         int retriesRemaining) {
        try {
            connection.setAutoCommit(false);
            if (isBlocked(identity.normalizedUsername())) {
                audit(null, "ATTEMPT_BLOCKED", null, null, null, "{\"username\":\"" + escape(identity.exactUsername()) + "\"}", now);
                connection.commit();
                return AttemptOutcome.of(AttemptState.BLOCKED, null);
            }

            WhitelistRequest active = findActiveByNameInternal(identity.normalizedUsername());
            if (active != null) {
                try (PreparedStatement statement = connection.prepareStatement("UPDATE whitelist_requests SET last_attempt_at=?, updated_at=?, attempt_count=attempt_count+1 WHERE id=?")) {
                    statement.setLong(1, millis(now));
                    statement.setLong(2, millis(now));
                    statement.setString(3, active.id().toString());
                    statement.executeUpdate();
                }
                audit(active.id(), "ATTEMPT_UPDATED", null, null, null,
                        "{\"exactUsername\":\"" + escape(identity.exactUsername()) + "\"}", now);
                insertOutbox("REQUEST_UPDATED", active.id(), now);
                connection.commit();
                return AttemptOutcome.of(AttemptState.PENDING, findByIdInternal(active.id()));
            }

            WhitelistRequest latestDenied = latestByStatus(identity.normalizedUsername(), RequestStatus.DENIED);
            if (latestDenied != null && latestDenied.resolvedAt() != null) {
                Instant until = latestDenied.resolvedAt().plus(denialCooldown);
                if (until.isAfter(now)) {
                    audit(latestDenied.id(), "DENIAL_COOLDOWN", null, null, null, "{}", now);
                    connection.commit();
                    return AttemptOutcome.deniedUntil(until, latestDenied);
                }
            }

            UUID id = UUID.randomUUID();
            WhitelistRequest created = new WhitelistRequest(id, identity, RequestStatus.PENDING, now, now, now, now,
                    1, null, null, null, null, null);
            insertRequest(created);
            insertOutbox("REQUEST_CREATED", id, now);
            audit(id, "REQUEST_CREATED", null, null, null, "{}", now);
            connection.commit();
            return AttemptOutcome.of(AttemptState.CREATED, created);
        } catch (SQLException exception) {
            rollbackQuietly();
            if (retriesRemaining > 0 && isRetryableAttemptRace(exception)) {
                resetAutoCommit();
                return recordAttempt(identity, now, denialCooldown, retriesRemaining - 1);
            }
            return AttemptOutcome.of(AttemptState.DEGRADED, null);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized Optional<WhitelistRequest> findById(UUID requestId) {
        try {
            return Optional.ofNullable(findByIdInternal(requestId));
        } catch (SQLException exception) {
            throw storageFailure("find_request", exception);
        }
    }

    @Override
    public synchronized Optional<WhitelistRequest> findActiveByName(String normalizedUsername) {
        try {
            return Optional.ofNullable(findActiveByNameInternal(normalizedUsername));
        } catch (SQLException exception) {
            throw storageFailure("find_active_request", exception);
        }
    }

    @Override
    public synchronized List<WhitelistRequest> findByStatus(Optional<RequestStatus> status, int limit) {
        String sql = "SELECT * FROM whitelist_requests" + (status.isPresent() ? " WHERE status=?" : "") + " ORDER BY updated_at DESC LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            if (status.isPresent()) statement.setString(index++, status.get().name());
            statement.setInt(index, Math.max(1, Math.min(limit, 500)));
            try (ResultSet result = statement.executeQuery()) {
                List<WhitelistRequest> requests = new ArrayList<>();
                while (result.next()) requests.add(readRequest(result));
                return requests;
            }
        } catch (SQLException exception) {
            throw storageFailure("find_requests_by_status", exception);
        }
    }

    @Override
    public synchronized void savePublication(UUID requestId, PublicationRef publication, Instant now) {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO request_publications(request_id,provider,external_container_id,external_message_id,state,created_at,updated_at) VALUES (?,?,?,?,?,?,?) ON CONFLICT(request_id,provider) DO UPDATE SET external_container_id=excluded.external_container_id, external_message_id=excluded.external_message_id, state=excluded.state, updated_at=excluded.updated_at")) {
            statement.setString(1, requestId.toString());
            statement.setString(2, publication.provider());
            statement.setString(3, publication.containerId());
            statement.setString(4, publication.messageId());
            statement.setString(5, "ACTIVE");
            statement.setLong(6, millis(now));
            statement.setLong(7, millis(now));
            statement.executeUpdate();
        } catch (SQLException error) {
            LOGGER.warn("storage.persistence.failed operation=save_publication requestId={} provider={} errorType={}",
                    requestId, publication.provider(), error.getClass().getSimpleName());
            throw storageFailure("save_publication", error);
        }
    }

    @Override
    public synchronized List<PublicationRef> publications(UUID requestId) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT provider, external_container_id, external_message_id FROM request_publications WHERE request_id=? AND state='ACTIVE'")) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) {
                List<PublicationRef> publications = new ArrayList<>();
                while (result.next()) publications.add(new PublicationRef(result.getString(1), result.getString(2), result.getString(3)));
                return publications;
            }
        } catch (SQLException exception) {
            throw storageFailure("find_publications", exception);
        }
    }

    @Override
    public synchronized DecisionClaim claimApproval(UUID requestId, AdminPrincipal actor, String reason, Instant now, UUID token) {
        try {
            connection.setAutoCommit(false);
            WhitelistRequest request = findByIdInternal(requestId);
            if (request == null) {
                connection.rollback();
                return new DecisionClaim(DecisionClaim.ClaimOutcome.NOT_FOUND, Optional.empty(), token);
            }
            if (request.status() == RequestStatus.RESOLVING) {
                connection.rollback();
                return new DecisionClaim(DecisionClaim.ClaimOutcome.ALREADY_RESOLVING, Optional.of(request), token);
            }
            if (request.status() != RequestStatus.PENDING) {
                connection.rollback();
                return new DecisionClaim(DecisionClaim.ClaimOutcome.ALREADY_RESOLVED, Optional.of(request), token);
            }
            try (PreparedStatement statement = connection.prepareStatement("UPDATE whitelist_requests SET status='RESOLVING', resolving_action='APPROVE', resolving_token=?, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, updated_at=? WHERE id=? AND status='PENDING'")) {
                statement.setString(1, token.toString());
                setActor(statement, 2, actor);
                statement.setString(5, reason);
                statement.setLong(6, millis(now));
                statement.setString(7, requestId.toString());
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    WhitelistRequest current = findByIdInternal(requestId);
                    if (current == null) {
                        return new DecisionClaim(DecisionClaim.ClaimOutcome.NOT_FOUND, Optional.empty(), token);
                    }
                    DecisionClaim.ClaimOutcome outcome = current.status() == RequestStatus.RESOLVING
                            ? DecisionClaim.ClaimOutcome.ALREADY_RESOLVING
                            : DecisionClaim.ClaimOutcome.ALREADY_RESOLVED;
                    return new DecisionClaim(outcome, Optional.of(current), token);
                }
            }
            audit(requestId, "DECISION_CLAIMED", actor, null, null, "{\"action\":\"APPROVE\"}", now);
            connection.commit();
            return new DecisionClaim(DecisionClaim.ClaimOutcome.CLAIMED, Optional.of(findByIdInternal(requestId)), token);
        } catch (SQLException exception) {
            rollbackQuietly();
            return new DecisionClaim(DecisionClaim.ClaimOutcome.NOT_FOUND, Optional.empty(), token);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized DecisionResultSnapshot resolveTerminal(UUID requestId, DecisionAction action, AdminPrincipal actor,
                                                                String reason, Instant now) {
        if (action == DecisionAction.APPROVE) throw new IllegalArgumentException("APPROVE requires claimApproval");
        try {
            connection.setAutoCommit(false);
            WhitelistRequest request = findByIdInternal(requestId);
            if (request == null) {
                connection.rollback();
                return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
            }
            if (request.status() != RequestStatus.PENDING) {
                connection.rollback();
                return new DecisionResultSnapshot(request.status() == RequestStatus.RESOLVING ? DecisionOutcome.RESOLVING : DecisionOutcome.ALREADY_RESOLVED,
                        Optional.of(request), "Request is already " + request.status().name().toLowerCase());
            }
            RequestStatus status = action == DecisionAction.BLOCK ? RequestStatus.BLOCKED : RequestStatus.DENIED;
            try (PreparedStatement statement = connection.prepareStatement("UPDATE whitelist_requests SET status=?, resolved_at=?, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, updated_at=? WHERE id=? AND status='PENDING'")) {
                statement.setString(1, status.name());
                statement.setLong(2, millis(now));
                setActor(statement, 3, actor);
                statement.setString(6, reason);
                statement.setLong(7, millis(now));
                statement.setString(8, requestId.toString());
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    WhitelistRequest current = findByIdInternal(requestId);
                    if (current == null) {
                        return new DecisionResultSnapshot(DecisionOutcome.NOT_FOUND, Optional.empty(), "Request not found");
                    }
                    DecisionOutcome outcome = current.status() == RequestStatus.RESOLVING
                            ? DecisionOutcome.RESOLVING : DecisionOutcome.ALREADY_RESOLVED;
                    return new DecisionResultSnapshot(outcome, Optional.of(current),
                            "Request is already " + current.status().name().toLowerCase());
                }
            }
            if (action == DecisionAction.BLOCK) {
                try (PreparedStatement statement = connection.prepareStatement("INSERT INTO identity_blocks(normalized_name, source_request_id, blocked_at, blocked_by_provider, blocked_by_external_id, blocked_by_display_name, reason) VALUES (?,?,?,?,?,?,?) ON CONFLICT(normalized_name) DO UPDATE SET source_request_id=excluded.source_request_id, blocked_at=excluded.blocked_at, blocked_by_provider=excluded.blocked_by_provider, blocked_by_external_id=excluded.blocked_by_external_id, blocked_by_display_name=excluded.blocked_by_display_name, reason=excluded.reason")) {
                    statement.setString(1, request.identity().normalizedUsername());
                    statement.setString(2, request.id().toString());
                    statement.setLong(3, millis(now));
                    setActor(statement, 4, actor);
                    statement.setString(7, reason);
                    statement.executeUpdate();
                }
            }
            audit(requestId, action == DecisionAction.BLOCK ? "REQUEST_BLOCKED" : "REQUEST_DENIED", actor, null, null, "{}", now);
            insertOutbox("REQUEST_RESOLVED", requestId, now);
            connection.commit();
            WhitelistRequest resolved = findByIdInternal(requestId);
            return new DecisionResultSnapshot(action == DecisionAction.BLOCK ? DecisionOutcome.BLOCKED : DecisionOutcome.DENIED,
                    Optional.of(resolved), "Request " + status.name().toLowerCase());
        } catch (SQLException exception) {
            rollbackQuietly();
            return new DecisionResultSnapshot(DecisionOutcome.FAILED, Optional.empty(), "Database error while resolving request");
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean finalizeApproval(UUID requestId, UUID token, AdminPrincipal actor, String reason, Instant now) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE whitelist_requests SET status='APPROVED', resolved_at=?, resolved_by_provider=?, resolved_by_external_id=?, resolved_by_display_name=?, resolution_reason=?, resolving_action=NULL, resolving_token=NULL, updated_at=? WHERE id=? AND status='RESOLVING' AND resolving_token=?")) {
                statement.setLong(1, millis(now));
                setActor(statement, 2, actor);
                statement.setString(5, reason);
                statement.setLong(6, millis(now));
                statement.setString(7, requestId.toString());
                statement.setString(8, token.toString());
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            audit(requestId, "REQUEST_APPROVED", actor, null, null, "{}", now);
            insertOutbox("REQUEST_RESOLVED", requestId, now);
            connection.commit();
            return true;
        } catch (SQLException exception) {
            rollbackQuietly();
            return false;
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized boolean resetApproval(UUID requestId, UUID token, AdminPrincipal actor, String error, Instant now) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("UPDATE whitelist_requests SET status='PENDING', resolving_action=NULL, resolving_token=NULL, resolved_by_provider=NULL, resolved_by_external_id=NULL, resolved_by_display_name=NULL, resolution_reason=NULL, updated_at=? WHERE id=? AND status='RESOLVING' AND resolving_token=?")) {
                statement.setLong(1, millis(now));
                statement.setString(2, requestId.toString());
                statement.setString(3, token.toString());
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            audit(requestId, "APPROVAL_FAILED", actor, null, null, "{\"error\":\"" + escape(error) + "\"}", now);
            insertOutbox("REQUEST_UPDATED", requestId, now);
            connection.commit();
            return true;
        } catch (SQLException exception) {
            rollbackQuietly();
            return false;
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized List<WhitelistRequest> findResolvingApprovals() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM whitelist_requests WHERE status='RESOLVING' AND resolving_action='APPROVE'")) {
            try (ResultSet result = statement.executeQuery()) {
                List<WhitelistRequest> requests = new ArrayList<>();
                while (result.next()) requests.add(readRequest(result));
                return requests;
            }
        } catch (SQLException exception) {
            return List.of();
        }
    }

    @Override
    public synchronized boolean unblock(String normalizedUsername, AdminPrincipal actor, String reason, Instant now) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM identity_blocks WHERE normalized_name=?")) {
                statement.setString(1, normalizedUsername);
                if (statement.executeUpdate() != 1) {
                    connection.rollback();
                    return false;
                }
            }
            audit(null, "BLOCK_REMOVED", actor, null, null, "{\"username\":\"" + escape(normalizedUsername) + "\"}", now);
            connection.commit();
            return true;
        } catch (SQLException exception) {
            rollbackQuietly();
            return false;
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized List<OutboxEvent> readyOutbox(Instant now, int limit) {
        return readyOutbox(now, limit, 3);
    }

    private List<OutboxEvent> readyOutbox(Instant now, int limit, int retriesRemaining) {
        try {
            connection.setAutoCommit(false);
            List<OutboxEvent> events = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM integration_outbox WHERE state='READY' AND available_at<=? ORDER BY created_at LIMIT ?")) {
                statement.setLong(1, millis(now));
                statement.setInt(2, Math.max(1, Math.min(limit, 100)));
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) events.add(readOutbox(result));
                }
            }
            List<OutboxEvent> claimed = new ArrayList<>();
            for (OutboxEvent event : events) {
                try (PreparedStatement statement = connection.prepareStatement("UPDATE integration_outbox SET state='PROCESSING', updated_at=? WHERE id=? AND state='READY'")) {
                    statement.setLong(1, millis(now));
                    statement.setString(2, event.id().toString());
                    if (statement.executeUpdate() == 1) claimed.add(event);
                }
            }
            connection.commit();
            return claimed.stream().map(event -> new OutboxEvent(event.id(), event.eventType(), event.aggregateId(), event.payloadJson(), OutboxEvent.OutboxState.PROCESSING, event.attempts(), event.availableAt(), event.createdAt(), now, event.lastError())).toList();
        } catch (SQLException exception) {
            rollbackQuietly();
            if (retriesRemaining > 0 && isRetryableAttemptRace(exception)) {
                resetAutoCommit();
                return readyOutbox(now, limit, retriesRemaining - 1);
            }
            throw storageFailure("claim_outbox", exception);
        } finally {
            resetAutoCommit();
        }
    }

    @Override
    public synchronized void completeOutbox(UUID outboxId, Instant now) {
        updateOutbox("UPDATE integration_outbox SET state='COMPLETE', updated_at=?, last_error=NULL WHERE id=? AND state='PROCESSING'", outboxId, now, null, null);
    }

    @Override
    public synchronized void retryOutbox(UUID outboxId, Instant nextAttempt, String error, Instant now) {
        updateOutbox("UPDATE integration_outbox SET state='READY', attempts=attempts+1, available_at=?, updated_at=?, last_error=? WHERE id=? AND state='PROCESSING'", outboxId, now, nextAttempt, error);
    }

    @Override
    public synchronized long pendingOutboxCount() {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM integration_outbox WHERE state!='COMPLETE'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            return -1;
        }
    }

    @Override
    public void close() throws SQLException {
        database.close();
    }

    private void updateOutbox(String sql, UUID id, Instant now, Instant nextAttempt, String error) {
        try {
            if (sql.contains("available_at")) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, millis(nextAttempt));
                    statement.setLong(2, millis(now));
                    statement.setString(3, error);
                    statement.setString(4, id.toString());
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setLong(1, millis(now));
                    statement.setString(2, id.toString());
                    statement.executeUpdate();
                }
            }
        } catch (SQLException sqlError) {
            LOGGER.warn("storage.persistence.failed operation=update_outbox outboxId={} errorType={}",
                    id, sqlError.getClass().getSimpleName());
            // Outbox failures are surfaced by the next status check and retry loop.
        }
    }

    private boolean isBlocked(String normalizedUsername) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM identity_blocks WHERE normalized_name=?")) {
            statement.setString(1, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        }
    }

    private WhitelistRequest latestByStatus(String normalizedUsername, RequestStatus status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM whitelist_requests WHERE normalized_name=? AND status=? ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, normalizedUsername);
            statement.setString(2, status.name());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readRequest(result) : null; }
        }
    }

    private WhitelistRequest findActiveByNameInternal(String normalizedUsername) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM whitelist_requests WHERE normalized_name=? AND status IN ('PENDING','RESOLVING') LIMIT 1")) {
            statement.setString(1, normalizedUsername);
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readRequest(result) : null; }
        }
    }

    private WhitelistRequest findByIdInternal(UUID requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM whitelist_requests WHERE id=?")) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) { return result.next() ? readRequest(result) : null; }
        }
    }

    private void insertRequest(WhitelistRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO whitelist_requests(id, normalized_name, requested_name, requested_uuid, status, created_at, updated_at, first_attempt_at, last_attempt_at, attempt_count) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, request.id().toString());
            statement.setString(2, request.identity().normalizedUsername());
            statement.setString(3, request.identity().exactUsername());
            statement.setString(4, request.identity().offlineUuid().toString());
            statement.setString(5, request.status().name());
            statement.setLong(6, millis(request.createdAt()));
            statement.setLong(7, millis(request.updatedAt()));
            statement.setLong(8, millis(request.firstAttemptAt()));
            statement.setLong(9, millis(request.lastAttemptAt()));
            statement.setLong(10, request.attemptCount());
            statement.executeUpdate();
        }
    }

    private void insertOutbox(String type, UUID aggregateId, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO integration_outbox(id,event_type,aggregate_id,payload_json,state,attempts,available_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, type);
            statement.setString(3, aggregateId.toString());
            statement.setString(4, "{\"requestId\":\"" + aggregateId + "\"}");
            statement.setString(5, OutboxEvent.OutboxState.READY.name());
            statement.setInt(6, 0);
            statement.setLong(7, millis(now));
            statement.setLong(8, millis(now));
            statement.setLong(9, millis(now));
            statement.executeUpdate();
        }
    }

    private void audit(UUID requestId, String type, AdminPrincipal actor, String ignored1, String ignored2, String details, Instant now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO audit_log(request_id,event_type,actor_provider,actor_external_id,actor_display_name,details_json,created_at) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, requestId == null ? null : requestId.toString());
            statement.setString(2, type);
            if (actor == null) {
                statement.setNull(3, Types.VARCHAR);
                statement.setNull(4, Types.VARCHAR);
                statement.setNull(5, Types.VARCHAR);
            } else setActor(statement, 3, actor);
            statement.setString(6, details);
            statement.setLong(7, millis(now));
            statement.executeUpdate();
        }
    }

    private static WhitelistRequest readRequest(ResultSet result) throws SQLException {
        UUID id = UUID.fromString(result.getString("id"));
        String exact = result.getString("requested_name");
        PlayerIdentity identity = PlayerIdentity.of(UUID.fromString(result.getString("requested_uuid")), exact);
        RequestStatus status = RequestStatus.valueOf(result.getString("status"));
        AdminPrincipal actor = result.getString("resolved_by_provider") == null ? null : new AdminPrincipal(
                result.getString("resolved_by_provider"), result.getString("resolved_by_external_id"), result.getString("resolved_by_display_name"));
        String action = result.getString("resolving_action");
        String token = result.getString("resolving_token");
        return new WhitelistRequest(id, identity, status, instant(result.getLong("created_at")), instant(result.getLong("updated_at")),
                instant(result.getLong("first_attempt_at")), instant(result.getLong("last_attempt_at")), result.getLong("attempt_count"),
                nullableInstant(result, "resolved_at"), actor, result.getString("resolution_reason"),
                action == null ? null : DecisionAction.valueOf(action), token == null ? null : UUID.fromString(token));
    }

    private static OutboxEvent readOutbox(ResultSet result) throws SQLException {
        return new OutboxEvent(UUID.fromString(result.getString("id")), result.getString("event_type"),
                UUID.fromString(result.getString("aggregate_id")), result.getString("payload_json"),
                OutboxEvent.OutboxState.valueOf(result.getString("state")), result.getInt("attempts"),
                instant(result.getLong("available_at")), instant(result.getLong("created_at")), instant(result.getLong("updated_at")), result.getString("last_error"));
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : instant(value);
    }

    private static Instant instant(long value) { return Instant.ofEpochMilli(value); }
    private static long millis(Instant instant) { return instant.toEpochMilli(); }

    private static void setActor(PreparedStatement statement, int start, AdminPrincipal actor) throws SQLException {
        statement.setString(start, actor.provider());
        statement.setString(start + 1, actor.externalId());
        statement.setString(start + 2, actor.displayName());
    }

    private static IllegalStateException storageFailure(String operation, SQLException error) {
        LOGGER.error("storage.degraded operation={} errorType={}", operation, error.getClass().getSimpleName());
        return new IllegalStateException("SQLite storage operation failed: " + operation, error);
    }

    private static boolean isRetryableAttemptRace(SQLException exception) {
        int errorCode = exception.getErrorCode();
        int primaryCode = errorCode & 0xff;
        if (primaryCode == 5 || primaryCode == 6) return true;
        String message = exception.getMessage();
        return (primaryCode == 19 && message != null && message.contains("whitelist_requests.normalized_name"))
                || (message != null && (message.contains("database is locked")
                || message.contains("database table is locked")));
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private void rollbackQuietly() { try { connection.rollback(); } catch (SQLException ignored) {} }
    private void resetAutoCommit() { try { connection.setAutoCommit(true); } catch (SQLException ignored) {} }
}
