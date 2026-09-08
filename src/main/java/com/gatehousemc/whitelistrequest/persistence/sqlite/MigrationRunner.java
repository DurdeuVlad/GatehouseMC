package com.gatehousemc.whitelistrequest.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

final class MigrationRunner {
    private MigrationRunner() {}

    static void initialize(Connection connection, int busyTimeoutMs) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + Math.max(0, busyTimeoutMs));
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS whitelist_requests (" +
                    "id TEXT PRIMARY KEY, normalized_name TEXT NOT NULL, requested_name TEXT NOT NULL, requested_uuid TEXT NOT NULL, " +
                    "status TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, first_attempt_at INTEGER NOT NULL, " +
                    "last_attempt_at INTEGER NOT NULL, attempt_count INTEGER NOT NULL, resolved_at INTEGER, " +
                    "resolved_by_provider TEXT, resolved_by_external_id TEXT, resolved_by_display_name TEXT, resolution_reason TEXT, " +
                    "resolving_action TEXT, resolving_token TEXT)");
            statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_whitelist_request_active_normalized ON whitelist_requests(normalized_name) WHERE status IN ('PENDING','RESOLVING')");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_whitelist_request_status_updated ON whitelist_requests(status, updated_at DESC)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_whitelist_request_normalized_history ON whitelist_requests(normalized_name, created_at DESC)");
            statement.execute("CREATE TABLE IF NOT EXISTS identity_blocks (" +
                    "normalized_name TEXT PRIMARY KEY, source_request_id TEXT, blocked_at INTEGER NOT NULL, " +
                    "blocked_by_provider TEXT NOT NULL, blocked_by_external_id TEXT NOT NULL, blocked_by_display_name TEXT, reason TEXT, " +
                    "FOREIGN KEY(source_request_id) REFERENCES whitelist_requests(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS request_publications (" +
                    "request_id TEXT NOT NULL, provider TEXT NOT NULL, external_container_id TEXT, external_message_id TEXT, " +
                    "state TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, last_error TEXT, " +
                    "PRIMARY KEY(request_id, provider), FOREIGN KEY(request_id) REFERENCES whitelist_requests(id))");
            statement.execute("CREATE TABLE IF NOT EXISTS integration_outbox (" +
                    "id TEXT PRIMARY KEY, event_type TEXT NOT NULL, aggregate_id TEXT NOT NULL, payload_json TEXT NOT NULL, " +
                    "state TEXT NOT NULL, attempts INTEGER NOT NULL, available_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                    "updated_at INTEGER NOT NULL, last_error TEXT)");
            statement.execute("CREATE INDEX IF NOT EXISTS ix_outbox_ready ON integration_outbox(state, available_at)");
            statement.execute("CREATE TABLE IF NOT EXISTS audit_log (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, request_id TEXT, event_type TEXT NOT NULL, actor_provider TEXT, " +
                    "actor_external_id TEXT, actor_display_name TEXT, details_json TEXT, created_at INTEGER NOT NULL, " +
                    "FOREIGN KEY(request_id) REFERENCES whitelist_requests(id))");
            statement.execute("INSERT OR IGNORE INTO schema_migrations(version, applied_at) VALUES (1, strftime('%s','now') * 1000)");
            statement.execute("UPDATE integration_outbox SET state='READY' WHERE state='PROCESSING'");
        }
    }
}
