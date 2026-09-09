package com.gatehousemc.persistence.sqlite;

import com.gatehousemc.domain.OutboxEvent;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQL operations for the integration_outbox table.
 */
final class OutboxSqlMapper {
    private final TransactionHelper tx;

    OutboxSqlMapper(TransactionHelper tx) {
        this.tx = tx;
    }

    void insert(String type, UUID aggregateId, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "INSERT INTO integration_outbox(id,event_type,aggregate_id,payload_json,state,attempts,available_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, type);
            statement.setString(3, aggregateId.toString());
            statement.setString(4, "{\"requestId\":\"" + aggregateId + "\"}");
            statement.setString(5, OutboxEvent.OutboxState.READY.name());
            statement.setInt(6, 0);
            statement.setLong(7, SqliteWorkflowRepository.millis(now));
            statement.setLong(8, SqliteWorkflowRepository.millis(now));
            statement.setLong(9, SqliteWorkflowRepository.millis(now));
            statement.executeUpdate();
        }
    }

    List<OutboxEvent> readyAndClaim(Instant now, int limit) throws SQLException {
        List<OutboxEvent> events = new ArrayList<>();
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "SELECT * FROM integration_outbox WHERE state='READY' AND available_at<=? ORDER BY created_at LIMIT ?")) {
            statement.setLong(1, SqliteWorkflowRepository.millis(now));
            statement.setInt(2, Math.max(1, Math.min(limit, 100)));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) events.add(readOutbox(result));
            }
        }
        List<OutboxEvent> claimed = new ArrayList<>();
        for (OutboxEvent event : events) {
            try (PreparedStatement statement = tx.connection().prepareStatement(
                    "UPDATE integration_outbox SET state='PROCESSING', updated_at=? WHERE id=? AND state='READY'")) {
                statement.setLong(1, SqliteWorkflowRepository.millis(now));
                statement.setString(2, event.id().toString());
                if (statement.executeUpdate() == 1) claimed.add(event);
            }
        }
        return claimed.stream().map(event -> new OutboxEvent(event.id(), event.eventType(), event.aggregateId(),
                event.payloadJson(), OutboxEvent.OutboxState.PROCESSING, event.attempts(), event.availableAt(),
                event.createdAt(), now, event.lastError())).toList();
    }

    void complete(UUID outboxId, Instant now) {
        update("UPDATE integration_outbox SET state='COMPLETE', updated_at=?, last_error=NULL WHERE id=? AND state='PROCESSING'", outboxId, now, null, null);
    }

    void retry(UUID outboxId, Instant nextAttempt, String error, Instant now) {
        update("UPDATE integration_outbox SET state='READY', attempts=attempts+1, available_at=?, updated_at=?, last_error=? WHERE id=? AND state='PROCESSING'", outboxId, now, nextAttempt, error);
    }

    long pendingCount() throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement("SELECT COUNT(*) FROM integration_outbox WHERE state!='COMPLETE'")) {
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0;
            }
        }
    }

    private void update(String sql, UUID id, Instant now, Instant nextAttempt, String error) {
        try {
            if (sql.contains("available_at")) {
                try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
                    statement.setLong(1, SqliteWorkflowRepository.millis(nextAttempt));
                    statement.setLong(2, SqliteWorkflowRepository.millis(now));
                    statement.setString(3, error);
                    statement.setString(4, id.toString());
                    statement.executeUpdate();
                }
            } else {
                try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
                    statement.setLong(1, SqliteWorkflowRepository.millis(now));
                    statement.setString(2, id.toString());
                    statement.executeUpdate();
                }
            }
        } catch (SQLException sqlError) {
            org.slf4j.LoggerFactory.getLogger(OutboxSqlMapper.class)
                    .warn("storage.persistence.failed operation=update_outbox outboxId={} errorType={}", id, sqlError.getClass().getSimpleName());
        }
    }

    private static OutboxEvent readOutbox(ResultSet result) throws SQLException {
        return new OutboxEvent(UUID.fromString(result.getString("id")), result.getString("event_type"),
                UUID.fromString(result.getString("aggregate_id")), result.getString("payload_json"),
                OutboxEvent.OutboxState.valueOf(result.getString("state")), result.getInt("attempts"),
                SqliteWorkflowRepository.instant(result.getLong("available_at")),
                SqliteWorkflowRepository.instant(result.getLong("created_at")),
                SqliteWorkflowRepository.instant(result.getLong("updated_at")),
                result.getString("last_error"));
    }
}
