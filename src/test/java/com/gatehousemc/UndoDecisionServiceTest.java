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
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoDecisionServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void undoApprovalRemovesFromWhitelistAndReopensRequest(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("UndoApprove"));
            UUID requestId = repository.findActiveByName("undoapprove").orElseThrow().id();
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache, Duration.ofDays(1));

            decisions.decide(requestId, DecisionAction.APPROVE, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();
            assertEquals(1, whitelist.addCalls.get());
            assertEquals(RequestStatus.APPROVED, repository.findById(requestId).orElseThrow().status());

            var undoResult = decisions.decide(requestId, DecisionAction.UNDO, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.UNDONE, undoResult.outcome());
            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
            assertEquals(1, whitelist.removeCalls.get());
            assertEquals(AdmissionState.Kind.PENDING, cache.get("undoapprove").kind());
        }
    }

    @Test
    void undoDeniedReopensRequestWithoutWhitelistMutation(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("UndoDeny"));
            UUID requestId = repository.findActiveByName("undodeny").orElseThrow().id();
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache, Duration.ofDays(1));

            decisions.decide(requestId, DecisionAction.DENY, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();
            assertEquals(RequestStatus.DENIED, repository.findById(requestId).orElseThrow().status());

            var undoResult = decisions.decide(requestId, DecisionAction.UNDO, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.UNDONE, undoResult.outcome());
            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
            assertEquals(0, whitelist.removeCalls.get());
            assertEquals(AdmissionState.Kind.PENDING, cache.get("undodeny").kind());
        }
    }

    @Test
    void undoBlockedRemovesBlockAndReopensRequest(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("UndoBlock"));
            UUID requestId = repository.findActiveByName("undoblock").orElseThrow().id();
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache, Duration.ofDays(1));

            decisions.decide(requestId, DecisionAction.BLOCK, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();
            assertEquals(RequestStatus.BLOCKED, repository.findById(requestId).orElseThrow().status());
            assertTrue(repository.isBlocked("undoblock"));

            var undoResult = decisions.decide(requestId, DecisionAction.UNDO, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.UNDONE, undoResult.outcome());
            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
            assertFalse(repository.isBlocked("undoblock"));
            assertEquals(0, whitelist.removeCalls.get());
            assertEquals(AdmissionState.Kind.PENDING, cache.get("undoblock").kind());
        }
    }

    @Test
    void undoPendingRequestReturnsAlreadyPending(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("UndoPending"));
            UUID requestId = repository.findActiveByName("undopending").orElseThrow().id();
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache, Duration.ofDays(1));

            var undoResult = decisions.decide(requestId, DecisionAction.UNDO, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.ALREADY_PENDING, undoResult.outcome());
            assertEquals(RequestStatus.PENDING, repository.findById(requestId).orElseThrow().status());
        }
    }

    @Test
    void undoNonexistentRequestReturnsNotFound(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            CountingWhitelist whitelist = new CountingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache, Duration.ofDays(1));

            var undoResult = decisions.decide(UUID.randomUUID(), DecisionAction.UNDO, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(DecisionOutcome.NOT_FOUND, undoResult.outcome());
        }
    }

    private static class CountingWhitelist implements VanillaWhitelistPort {
        final AtomicInteger addCalls = new AtomicInteger();
        final AtomicInteger removeCalls = new AtomicInteger();

        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(false);
        }

        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
            addCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
            removeCalls.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }
    }
}
