package com.gatehousemc.persistence.sqlite;

import com.google.gson.JsonParser;
import com.gatehousemc.application.RequestAdmissionCache;
import com.gatehousemc.application.WhitelistRequestService;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.port.ClockPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteWorkflowRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final ClockPort CLOCK = () -> NOW;

    @Test
    void freshDatabaseMigratesToCurrentSchema(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("requests.sqlite");
        try (SqliteDatabase database = new SqliteDatabase(path, 5000)) {
            assertEquals(1, scalarLong(database.connection(), "SELECT MAX(version) FROM schema_migrations"));
            assertEquals(1, scalarLong(database.connection(),
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='whitelist_requests'"));
            assertEquals(1, scalarLong(database.connection(), "PRAGMA foreign_keys"));
            assertEquals(5000, scalarLong(database.connection(), "PRAGMA busy_timeout"));
            assertEquals("wal", scalarString(database.connection(), "PRAGMA journal_mode"));
        }
    }

    @Test
    void reopenPreservesRequestAndAuditHistory(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("requests.sqlite");
        UUID requestId;
        try (SqliteDatabase database = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            service.recordAttempt(PlayerIdentity.of("Alice"));
            requestId = repository.findActiveByName("alice").orElseThrow().id();
            assertEquals(1, scalarLong(database.connection(), "SELECT COUNT(*) FROM audit_log"));
        }

        try (SqliteDatabase database = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            assertEquals("Alice", repository.findById(requestId).orElseThrow().identity().exactUsername());
            assertEquals(1, scalarLong(database.connection(), "SELECT COUNT(*) FROM audit_log"));
        }
    }

    @Test
    void rejectsDatabaseFromAnUnsupportedFutureSchema(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("future.sqlite");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE schema_migrations (version INTEGER PRIMARY KEY, applied_at INTEGER NOT NULL)");
            statement.execute("INSERT INTO schema_migrations(version, applied_at) VALUES (99, 0)");
        }

        assertThrows(SQLException.class, () -> new SqliteDatabase(path, 5000));
    }

    @Test
    void concurrentAttemptsCannotCreateDuplicateActiveRequests(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("concurrent.sqlite");
        try (SqliteDatabase firstDatabase = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository first = new SqliteWorkflowRepository(firstDatabase);
             SqliteDatabase secondDatabase = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository second = new SqliteWorkflowRepository(secondDatabase)) {
            PlayerIdentity identity = PlayerIdentity.of("ConcurrentPlayer");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<?> firstAttempt = CompletableFuture.runAsync(() -> recordAfterStart(first, identity, ready, start));
            CompletableFuture<?> secondAttempt = CompletableFuture.runAsync(() -> recordAfterStart(second, identity, ready, start));
            ready.await();
            start.countDown();
            firstAttempt.join();
            secondAttempt.join();

            assertEquals(1, first.findByStatus(Optional.of(com.gatehousemc.domain.RequestStatus.PENDING), 10).size());
        }
    }

    @Test
    void auditDetailsRemainValidJsonWhenFailureContainsControlCharacters(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("audit.sqlite");
        try (SqliteDatabase database = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            service.recordAttempt(PlayerIdentity.of("AuditPlayer"));
            UUID requestId = repository.findActiveByName("auditplayer").orElseThrow().id();
            var claim = repository.claimApproval(requestId, AdminPrincipal.console(), "", NOW, UUID.randomUUID());

            assertDoesNotThrow(() -> repository.resetApproval(requestId, claim.token(), AdminPrincipal.console(), "line\nbreak\t", NOW));
            try (Statement statement = database.connection().createStatement();
                 ResultSet result = statement.executeQuery("SELECT details_json FROM audit_log WHERE event_type='APPROVAL_FAILED'")) {
                assertTrue(result.next());
                assertDoesNotThrow(() -> JsonParser.parseString(result.getString(1)));
            }
        }
    }

    private static void recordAfterStart(SqliteWorkflowRepository repository, PlayerIdentity identity,
                                         CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            repository.recordAttempt(identity, NOW, Duration.ofDays(1));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private static WhitelistRequestService service(SqliteWorkflowRepository repository) {
        return new WhitelistRequestService(repository, CLOCK, Duration.ofDays(1), new RequestAdmissionCache(CLOCK));
    }

    private static String scalarString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : "";
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getLong(1) : 0;
        }
    }
}
