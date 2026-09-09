package com.gatehousemc;

import com.gatehousemc.application.AdmissionState;
import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.RequestAdmissionCache;
import com.gatehousemc.application.WhitelistRequestService;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.DecisionOutcome;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.port.VanillaWhitelistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void successfulApprovalUpdatesVanillaOnceAndClearsPendingCache(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            PlayerIdentity identity = PlayerIdentity.of("Alice");
            requests.recordAttempt(identity);
            UUID requestId = repository.findActiveByName("alice").orElseThrow().id();
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache, Duration.ofDays(1));

            var result = decisions.decide(requestId, DecisionAction.APPROVE, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.APPROVED, result.outcome());
            assertEquals(RequestStatus.APPROVED, repository.findById(requestId).orElseThrow().status());
            assertEquals(1, whitelist.addCalls.get());
            assertEquals(AdmissionState.Kind.UNKNOWN, cache.get("alice").kind());
        }
    }

    @Test
    void losingApprovalRefreshesCacheFromDurableTerminalState(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("LosingApproval"));
            UUID requestId = repository.findActiveByName("losingapproval").orElseThrow().id();
            DecisionService decisions = new DecisionService(repository, new CountingWhitelist(), () -> NOW, cache);
            assertEquals(DecisionOutcome.APPROVED, decisions.decide(requestId, DecisionAction.APPROVE,
                    AdminPrincipal.console(), Optional.empty()).toCompletableFuture().join().outcome());
            cache.put("losingapproval", AdmissionState.pending());

            assertEquals(DecisionOutcome.ALREADY_RESOLVED, decisions.decide(requestId, DecisionAction.APPROVE,
                    AdminPrincipal.console(), Optional.empty()).toCompletableFuture().join().outcome());
            assertEquals(AdmissionState.Kind.UNKNOWN, cache.get("losingapproval").kind());
        }
    }

    @Test
    void failedWhitelistMutationReturnsRequestToPending(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("Bob"));
            UUID requestId = repository.findActiveByName("bob").orElseThrow().id();
            DecisionService decisions = new DecisionService(repository, new FailingWhitelist(), () -> NOW, cache);

            var result = decisions.decide(requestId, DecisionAction.APPROVE, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.FAILED, result.outcome());
            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
            assertEquals(AdmissionState.Kind.PENDING, cache.get("bob").kind());
        }
    }

    @Test
    void terminalDecisionAuditStoresActorOrigin(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("Carol"));
            UUID requestId = repository.findActiveByName("carol").orElseThrow().id();
            AdminPrincipal actor = new AdminPrincipal("discord", "user-123", "Moderator");
            DecisionService decisions = new DecisionService(repository, new CountingWhitelist(), () -> NOW, cache);

            assertEquals(DecisionOutcome.DENIED, decisions.decide(requestId, DecisionAction.DENY, actor, Optional.of("reason"))
                    .toCompletableFuture().join().outcome());
            try (Connection auditConnection = DriverManager.getConnection("jdbc:sqlite:" + temp.resolve("requests.sqlite"));
                 Statement statement = auditConnection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT actor_provider, actor_external_id FROM audit_log WHERE event_type='REQUEST_DENIED'")) {
                assertTrue(result.next());
                assertEquals("discord", result.getString(1));
                assertEquals("user-123", result.getString(2));
            }
        }
    }

    @Test
    void recoveryFinalizesWhenProfileIsAlreadyWhitelisted(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("RecoveredPlayer"));
            UUID requestId = repository.findActiveByName("recoveredplayer").orElseThrow().id();
            repository.claimApproval(requestId, AdminPrincipal.console(), "", NOW, UUID.randomUUID());
            DecisionService decisions = new DecisionService(repository, new RecoveryWhitelist(true, null), () -> NOW, cache);

            decisions.recoverInterruptedApprovals().toCompletableFuture().join();

            assertEquals(RequestStatus.APPROVED, repository.findById(requestId).orElseThrow().status());
            assertEquals(AdmissionState.Kind.UNKNOWN, cache.get("recoveredplayer").kind());
            decisions.recoverInterruptedApprovals().toCompletableFuture().join();
            assertEquals(RequestStatus.APPROVED, repository.findById(requestId).orElseThrow().status());
        }
    }

    @Test
    void recoveryResetsWhenProfileIsNotWhitelisted(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("UnrecoverPlayer"));
            UUID requestId = repository.findActiveByName("unrecoverplayer").orElseThrow().id();
            repository.claimApproval(requestId, AdminPrincipal.console(), "", NOW, UUID.randomUUID());
            DecisionService decisions = new DecisionService(repository, new RecoveryWhitelist(false, null), () -> NOW, cache);

            decisions.recoverInterruptedApprovals().toCompletableFuture().join();

            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
            assertEquals(AdmissionState.Kind.PENDING, cache.get("unrecoverplayer").kind());
        }
    }

    @Test
    void recoveryFailureResetsClaimWithoutLeavingItStuck(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("FailedRecovery"));
            UUID requestId = repository.findActiveByName("failedrecovery").orElseThrow().id();
            repository.claimApproval(requestId, AdminPrincipal.console(), "", NOW, UUID.randomUUID());
            DecisionService decisions = new DecisionService(repository,
                    new RecoveryWhitelist(false, new IllegalStateException("server unavailable")), () -> NOW, cache);

            decisions.recoverInterruptedApprovals().toCompletableFuture().join();

            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
            assertEquals(AdmissionState.Kind.PENDING, cache.get("failedrecovery").kind());
        }
    }

    @Test
    void decisionPersistenceRunsOnProvidedWorkerExecutor(@TempDir Path temp) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "decision-worker"));
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("WorkerPlayer"));
            UUID requestId = repository.findActiveByName("workerplayer").orElseThrow().id();
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache,
                    Duration.ofDays(1), executor);

            assertEquals(DecisionOutcome.APPROVED, decisions.decide(requestId, DecisionAction.APPROVE,
                    AdminPrincipal.console(), Optional.empty()).toCompletableFuture().join().outcome());
            assertEquals("decision-worker", whitelist.addThread.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static class CountingWhitelist implements VanillaWhitelistPort {
        protected final AtomicInteger addCalls = new AtomicInteger();
        protected final AtomicInteger removeCalls = new AtomicInteger();
        protected final AtomicReference<String> addThread = new AtomicReference<>();

        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(false);
        }

        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
            addCalls.incrementAndGet();
            addThread.set(Thread.currentThread().getName());
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
            removeCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecoveryWhitelist implements VanillaWhitelistPort {
        private final boolean allowed;
        private final Throwable failure;

        private RecoveryWhitelist(boolean allowed, Throwable failure) {
            this.allowed = allowed;
            this.failure = failure;
        }

        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
            return failure == null ? CompletableFuture.completedFuture(allowed) : CompletableFuture.failedFuture(failure);
        }

        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FailingWhitelist extends CountingWhitelist {
        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
            addCalls.incrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("whitelist unavailable"));
        }
    }
}
