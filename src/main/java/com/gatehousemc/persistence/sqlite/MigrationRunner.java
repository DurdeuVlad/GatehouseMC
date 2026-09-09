package com.gatehousemc.persistence.sqlite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

final class MigrationRunner {
    private static final int CURRENT_VERSION = 1;

    private MigrationRunner() {}

    static void initialize(Connection connection, int busyTimeoutMs) throws SQLException {
        int configuredBusyTimeout = Math.max(0, busyTimeoutMs);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = " + configuredBusyTimeout);
        }
        if (!"wal".equalsIgnoreCase(journalMode(connection))) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode = WAL");
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
        }
        verifyPragmas(connection, configuredBusyTimeout);

        int appliedVersion = appliedVersion(connection);
        if (appliedVersion > CURRENT_VERSION) {
            throw new SQLException("Unsupported future schema version: " + appliedVersion);
        }
        while (appliedVersion < CURRENT_VERSION) {
            int nextVersion = appliedVersion + 1;
            applyMigration(connection, nextVersion);
            recordMigration(connection, nextVersion);
            appliedVersion = nextVersion;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE integration_outbox SET state='READY' WHERE state='PROCESSING'");
        }
    }

    private static String journalMode(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode")) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private static void verifyPragmas(Connection connection, int expectedBusyTimeout) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet foreignKeys = statement.executeQuery("PRAGMA foreign_keys")) {
            if (!foreignKeys.next() || foreignKeys.getInt(1) != 1) {
                throw new SQLException("SQLite foreign_keys pragma is not enabled");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet busyTimeout = statement.executeQuery("PRAGMA busy_timeout")) {
            if (!busyTimeout.next() || busyTimeout.getInt(1) != expectedBusyTimeout) {
                throw new SQLException("SQLite busy_timeout pragma was not applied");
            }
        }
        try (Statement statement = connection.createStatement();
             ResultSet journalMode = statement.executeQuery("PRAGMA journal_mode")) {
            if (!journalMode.next() || !"wal".equalsIgnoreCase(journalMode.getString(1))) {
                throw new SQLException("SQLite WAL journal mode is unavailable");
            }
        }
    }

    private static int appliedVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_migrations")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void applyMigration(Connection connection, int version) throws SQLException {
        if (version == 1) {
            applyVersionOne(connection);
            return;
        }
        throw new SQLException("No migration registered for schema version: " + version);
    }

    private static void recordMigration(Connection connection, int version) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (" + version + ", strftime('%s','now') * 1000)");
        }
    }

    private static void applyVersionOne(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
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
        }
    }
}
