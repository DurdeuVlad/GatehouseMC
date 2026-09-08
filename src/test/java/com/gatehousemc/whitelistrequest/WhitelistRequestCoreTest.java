package com.gatehousemc.whitelistrequest;

import com.gatehousemc.whitelistrequest.application.DecisionService;
import com.gatehousemc.whitelistrequest.application.RequestAdmissionCache;
import com.gatehousemc.whitelistrequest.application.WhitelistRequestService;
import com.gatehousemc.whitelistrequest.domain.*;
import com.gatehousemc.whitelistrequest.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.whitelistrequest.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.VanillaWhitelistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class WhitelistRequestCoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final ClockPort clock = () -> NOW;

    @Test
    void normalizesWithoutChangingApprovalTarget() {
        PlayerIdentity identity = PlayerIdentity.of(UUID.randomUUID(), "Alice");
        assertEquals("alice", identity.normalizedUsername());
        assertEquals("Alice", identity.exactUsername());
    }

    @Test
    void createsOnePendingRequestAndDedupeAttempts(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            PlayerIdentity first = PlayerIdentity.of(UUID.randomUUID(), "Alice");
            PlayerIdentity caseVariant = PlayerIdentity.of(UUID.randomUUID(), "ALICE");

            AttemptOutcome created = service.recordAttempt(first);
            AttemptOutcome repeated = service.recordAttempt(caseVariant);

            assertEquals(AttemptState.CREATED, created.state());
            assertEquals(AttemptState.PENDING, repeated.state());
            assertEquals(1, repository.findByStatus(Optional.of(RequestStatus.PENDING), 10).size());
            WhitelistRequest request = repository.findActiveByName("alice").orElseThrow();
            assertEquals("Alice", request.identity().exactUsername());
            assertEquals(first.offlineUuid(), request.identity().offlineUuid());
            assertEquals(2, request.attemptCount());
            assertEquals(1, repository.readyOutbox(NOW, 10).size());
        }
    }

    @Test
    void denialCooldownAndBlockAreDurable(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            PlayerIdentity identity = PlayerIdentity.of(UUID.randomUUID(), "Bob");
            service.recordAttempt(identity);
            UUID requestId = repository.findActiveByName("bob").orElseThrow().id();
            DecisionService decisions = new DecisionService(repository, new FakeWhitelist(), clock, service.cache());

            DecisionResult denied = decisions.decide(requestId, DecisionAction.DENY, AdminPrincipal.console(), Optional.of("not yet")).toCompletableFuture().join();
            assertEquals(DecisionOutcome.DENIED, denied.outcome());
            assertEquals(AttemptState.DENIED_COOLDOWN, service.recordAttempt(identity).state());

            Instant later = NOW.plus(Duration.ofDays(2));
            AttemptOutcome afterCooldown = repository.recordAttempt(identity, later, Duration.ofDays(1));
            assertEquals(AttemptState.CREATED, afterCooldown.state());
            UUID secondId = repository.findActiveByName("bob").orElseThrow().id();
            DecisionService laterDecisions = new DecisionService(repository, new FakeWhitelist(), () -> later, service.cache());
            DecisionResult blocked = laterDecisions.decide(secondId, DecisionAction.BLOCK, AdminPrincipal.console(), Optional.empty()).toCompletableFuture().join();
            assertEquals(DecisionOutcome.BLOCKED, blocked.outcome());
            assertEquals(AttemptState.BLOCKED, service.recordAttempt(identity).state());
        }
    }

    @Test
    void approvalClaimWinsAgainstConcurrentDeny(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            PlayerIdentity identity = PlayerIdentity.of(UUID.randomUUID(), "Carol");
            service.recordAttempt(identity);
            UUID requestId = repository.findActiveByName("carol").orElseThrow().id();
            CompletableFuture<Void> whitelistResult = new CompletableFuture<>();
            DecisionService decisions = new DecisionService(repository, new FakeWhitelist(whitelistResult), clock, service.cache());

            var approval = decisions.decide(requestId, DecisionAction.APPROVE, AdminPrincipal.console(), Optional.empty());
            DecisionResult loser = decisions.decide(requestId, DecisionAction.DENY, AdminPrincipal.console(), Optional.empty()).toCompletableFuture().join();
            assertEquals(DecisionOutcome.RESOLVING, loser.outcome());
            whitelistResult.complete(null);
            assertEquals(DecisionOutcome.APPROVED, approval.toCompletableFuture().join().outcome());
            assertEquals(RequestStatus.APPROVED, repository.findById(requestId).orElseThrow().status());
        }
    }

    private WhitelistRequestService service(SqliteWorkflowRepository repository) {
        return new WhitelistRequestService(repository, clock, Duration.ofDays(1), new RequestAdmissionCache());
    }

    private static final class FakeWhitelist implements VanillaWhitelistPort {
        private final CompletableFuture<Void> addResult;

        private FakeWhitelist() { this(CompletableFuture.completedFuture(null)); }
        private FakeWhitelist(CompletableFuture<Void> addResult) { this.addResult = addResult; }

        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) { return CompletableFuture.completedFuture(false); }
        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) { return addResult; }
    }
}
