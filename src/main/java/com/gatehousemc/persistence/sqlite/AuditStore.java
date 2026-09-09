package com.gatehousemc.persistence.sqlite;

import com.gatehousemc.domain.AdminPrincipal;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes audit log rows with Gson-built JSON payloads.
 * Replaces the hand-rolled escape() approach.
 */
final class AuditStore {
    private static final Gson GSON = new Gson();

    private final TransactionHelper tx;

    AuditStore(TransactionHelper tx) {
        this.tx = tx;
    }

    void record(UUID requestId, String type, AdminPrincipal actor, String details, Instant now) throws SQLException {
        try (PreparedStatement statement = tx.connection().prepareStatement(
                "INSERT INTO audit_log(request_id,event_type,actor_provider,actor_external_id,actor_display_name,details_json,created_at) VALUES (?,?,?,?,?,?,?)")) {
            statement.setString(1, requestId == null ? null : requestId.toString());
            statement.setString(2, type);
            if (actor == null) {
                statement.setNull(3, Types.VARCHAR);
                statement.setNull(4, Types.VARCHAR);
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(3, actor.provider());
                statement.setString(4, actor.externalId());
                statement.setString(5, actor.displayName());
            }
            statement.setString(6, details);
            statement.setLong(7, SqliteWorkflowRepository.millis(now));
            statement.executeUpdate();
        }
    }

    void record(UUID requestId, String type, AdminPrincipal actor, JsonObject details, Instant now) throws SQLException {
        record(requestId, type, actor, GSON.toJson(details), now);
    }

    static String jsonField(String key, String value) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key, value);
        return GSON.toJson(obj);
    }

    static String jsonField(String key1, String value1, String key2, String value2) {
        JsonObject obj = new JsonObject();
        obj.addProperty(key1, value1);
        obj.addProperty(key2, value2);
        return GSON.toJson(obj);
    }

    static String emptyJson() {
        return "{}";
    }
}
