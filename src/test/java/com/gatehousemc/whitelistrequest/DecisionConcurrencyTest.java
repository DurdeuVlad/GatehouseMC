package com.gatehousemc.whitelistrequest;

import com.gatehousemc.whitelistrequest.application.DecisionService;
import com.gatehousemc.whitelistrequest.application.RequestAdmissionCache;
import com.gatehousemc.whitelistrequest.application.WhitelistRequestService;
import com.gatehousemc.whitelistrequest.domain.AdminPrincipal;
import com.gatehousemc.whitelistrequest.domain.DecisionAction;
import com.gatehousemc.whitelistrequest.domain.DecisionOutcome;
import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import com.gatehousemc.whitelistrequest.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.whitelistrequest.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.whitelistrequest.port.VanillaWhitelistPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecisionConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void approvalClaimWinsAgainstConcurrentDenial(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("requests.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService requests = new WhitelistRequestService(repository, () -> NOW, Duration.ofDays(1), cache);
            requests.recordAttempt(PlayerIdentity.of("RacePlayer"));
            UUID requestId = repository.findActiveByName("raceplayer").orElseThrow().id();
            BlockingWhitelist whitelist = new BlockingWhitelist();
            DecisionService decisions = new DecisionService(repository, whitelist, () -> NOW, cache);

            CompletableFuture<DecisionOutcome> approval = CompletableFuture.supplyAsync(() -> decisions
                    .decide(requestId, DecisionAction.APPROVE, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join().outcome());
            whitelist.claimed.await();
            DecisionOutcome denial = decisions.decide(requestId, DecisionAction.DENY, AdminPrincipal.console(), Optional.empty())
                    .toCompletableFuture().join().outcome();
            whitelist.complete();

            assertEquals(DecisionOutcome.RESOLVING, denial);
            assertEquals(DecisionOutcome.APPROVED, approval.join());
        }
    }

    private static final class BlockingWhitelist implements VanillaWhitelistPort {
        private final CountDownLatch claimed = new CountDownLatch(1);
        private final CompletableFuture<Void> result = new CompletableFuture<>();

        @Override public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
            return CompletableFuture.completedFuture(false);
        }

        @Override public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
            claimed.countDown();
            return result;
        }

        private void complete() {
            result.complete(null);
        }
    }
}
