package com.gatehousemc.persistence.sqlite;

import com.gatehousemc.application.SetupSessionStore;
import com.gatehousemc.port.ClockPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteSetupSessionPersistenceTest {
    @Test
    void setupSessionSurvivesDatabaseRestartAndOnlyHashIsStored(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("setup.sqlite");
        ClockPort clock = () -> Instant.parse("2026-01-01T00:00:00Z");
        SetupSessionStore.Issued issued;
        try (SqliteDatabase database = new SqliteDatabase(path, 5000)) {
            SetupSessionStore store = new SetupSessionStore(clock, new SecureRandom(),
                    new SqliteSetupSessionPersistence(database));
            issued = store.issue("discord");
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM provider_setup_sessions WHERE code_hash = '" + issued.code() + "'"));
        }
        try (SqliteDatabase database = new SqliteDatabase(path, 5000)) {
            SetupSessionStore store = new SetupSessionStore(clock, new SecureRandom(),
                    new SqliteSetupSessionPersistence(database));
            assertEquals(SetupSessionStore.ConsumeStatus.CONSUMED,
                    store.consume("discord", issued.code()).status());
            assertTrue(scalar(database, "SELECT COUNT(*) FROM provider_setup_sessions") == 1);
        }
    }

    private static long scalar(SqliteDatabase database, String sql) throws Exception {
        try (var statement = database.connection().createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }
}
