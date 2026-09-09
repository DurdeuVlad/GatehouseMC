package com.gatehousemc;

import com.gatehousemc.application.AdmissionState;
import com.gatehousemc.application.DecisionService;
import com.gatehousemc.application.RequestAdmissionCache;
import com.gatehousemc.application.WhitelistRequestService;
import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.AttemptState;
import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.PlayerIdentity;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

class WhitelistRequestServiceTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void denialCooldownUsesTheInjectedWorkflowClock() {
        RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
        cache.put("alice", AdmissionState.deniedUntil(NOW.plusSeconds(60)));

        assertEquals(AdmissionState.Kind.DENIED, cache.get("alice").kind());
    }

    @Test
    void expiredDenialIsRemovedUsingTheInjectedWorkflowClock() {
        RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
        cache.put("alice", AdmissionState.deniedUntil(NOW.minusSeconds(1)));

        assertEquals(AdmissionState.Kind.UNKNOWN, cache.get("alice").kind());
    }

    @Test
    void firstAndCaseVariantAttemptsShareOneCanonicalRequest(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            PlayerIdentity first = PlayerIdentity.of("Alice");
            PlayerIdentity variant = PlayerIdentity.of("ALICE");

            assertEquals(AttemptState.CREATED, service.recordAttempt(first).state());
            assertEquals(AttemptState.PENDING, service.recordAttempt(variant).state());

            var request = repository.findActiveByName("alice").orElseThrow();
            assertEquals("Alice", request.identity().exactUsername());
            assertEquals(first.offlineUuid(), request.identity().offlineUuid());
            assertEquals(2, request.attemptCount());
        }
    }

    @Test
    void blockedIdentityDoesNotCreateAnotherRequest(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            WhitelistRequestService service = service(repository);
            PlayerIdentity identity = PlayerIdentity.of("BlockedPlayer");
            service.recordAttempt(identity);
            UUID requestId = repository.findActiveByName("blockedplayer").orElseThrow().id();
            DecisionService decisions = new DecisionService(repository, new NoopWhitelist(), () -> NOW, service.cache());

            decisions.decide(requestId, DecisionAction.BLOCK, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join();

            assertEquals(AttemptState.BLOCKED, service.recordAttempt(identity).state());
            assertEquals(0, repository.findByStatus(Optional.of(com.gatehousemc.domain.RequestStatus.PENDING), 10).size());
        }
    }

    private static WhitelistRequestService service(SqliteWorkflowRepository repository) {
        return new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), new RequestAdmissionCache(() -> NOW));
    }

    private static final class NoopWhitelist implements VanillaWhitelistPort {
        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(false);
        }

        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
