package com.gatehousemc.application;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.AttemptOutcome;
import com.gatehousemc.domain.AttemptState;
import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.DecisionClaim;
import com.gatehousemc.domain.OutboxEvent;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.PublicationRef;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.port.ClockPort;
import com.gatehousemc.port.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for "requests appear only after reload/restart": one transient
 * persistence failure latched the admission path as permanently degraded, so no
 * subsequent whitelist denial created a request until the runtime was rebuilt.
 * The latch must clear as soon as persistence works again, and the worker must
 * keep probing storage so recovery does not require an operator action.
 */
class AdmissionRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration PROBE_INTERVAL = Duration.ofSeconds(10);

    @Test
    void degradedLatchClearsWhenPersistenceRecovers(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("recovery.sqlite"), 5000);
             SqliteWorkflowRepository real = new SqliteWorkflowRepository(database)) {
            FlakyRepository repository = new FlakyRepository(real);
            RequestAdmissionCache cache = new RequestAdmissionCache(() -> NOW);
            WhitelistRequestService service = new WhitelistRequestService(repository, () -> NOW, Duration.ZERO, cache);
            PlayerIdentity identity = PlayerIdentity.of("UnluckyPlayer");

            repository.failing.set(true);
            assertEquals(AttemptState.DEGRADED, service.recordAttempt(identity).state());
            assertTrue(cache.isDegraded(), "transient failure should mark the intake degraded");

            repository.failing.set(false);
            assertEquals(AttemptState.CREATED, service.recordAttempt(identity).state());
            assertFalse(cache.isDegraded(), "a successful write must clear the degraded latch");
            assertTrue(real.findActiveByName("unluckyplayer").isPresent(),
                    "the recovered attempt must be persisted");
        }
    }

    @Test
    void degradedWorkerKeepsProbingAndHealsWithoutRestart(@TempDir Path temp) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("probe.sqlite"), 5000);
             SqliteWorkflowRepository real = new SqliteWorkflowRepository(database)) {
            FlakyRepository repository = new FlakyRepository(real);
            RequestAdmissionCache cache = new RequestAdmissionCache(clock);
            WhitelistRequestService service = new WhitelistRequestService(repository, clock, Duration.ZERO, cache);
            AdmissionWorker worker = new AdmissionWorker(64, service, clock,
                    new ActiveRequestCounter(0), 30, 10, 500, 5, PROBE_INTERVAL);
            worker.start();
            try {
                PlayerIdentity identity = PlayerIdentity.of("ProbePlayer");
                repository.failing.set(true);
                assertEquals(AdmissionWorker.OfferStatus.ACCEPTED,
                        worker.offer(identity, AdmissionState.Kind.UNKNOWN).status());
                await(() -> cache.isDegraded(), "the failing attempt should degrade the intake");

                // While degraded the caller still gets the honest unavailable signal,
                // but the worker must keep a bounded probe alive instead of dropping
                // every attempt until restart.
                assertEquals(AdmissionWorker.OfferStatus.DEGRADED,
                        worker.offer(identity, AdmissionState.Kind.UNKNOWN).status());
                clock.advance(PROBE_INTERVAL.plusSeconds(1));
                repository.failing.set(false);
                assertEquals(AdmissionWorker.OfferStatus.DEGRADED,
                        worker.offer(identity, AdmissionState.Kind.UNKNOWN).status());

                await(() -> !cache.isDegraded(), "a successful probe must clear the degraded latch");
                assertEquals(AdmissionWorker.OfferStatus.ACCEPTED,
                        worker.offer(PlayerIdentity.of("HealthyPlayer"), AdmissionState.Kind.UNKNOWN).status());
                await(() -> real.findActiveByName("healthyplayer").isPresent(),
                        "post-recovery attempts must persist");
            } finally {
                worker.close();
            }
        }
    }

    private static void await(Check check, String description) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.run()) return;
            Thread.sleep(10);
        }
        assertTrue(check.run(), description);
    }

    private interface Check {
        boolean run();
    }

    private static final class MutableClock implements ClockPort {
        private final AtomicReference<Instant> now;
        private MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        void advance(Duration delta) { now.updateAndGet(value -> value.plus(delta)); }
        @Override public Instant now() { return now.get(); }
    }

    /** Repository that reports persistence degradation while {@code failing} and delegates otherwise. */
    private static final class FlakyRepository implements WorkflowRepository {
        private final WorkflowRepository delegate;
        private final AtomicBoolean failing = new AtomicBoolean();

        private FlakyRepository(WorkflowRepository delegate) {
            this.delegate = delegate;
        }

        @Override
        public AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown) {
            if (failing.get()) return AttemptOutcome.of(AttemptState.DEGRADED, null);
            return delegate.recordAttempt(identity, now, denialCooldown);
        }

        @Override
        public AttemptOutcome recordAttempt(PlayerIdentity identity, Instant now, Duration denialCooldown,
                                            long attemptDelta) {
            if (failing.get()) return AttemptOutcome.of(AttemptState.DEGRADED, null);
            return delegate.recordAttempt(identity, now, denialCooldown, attemptDelta);
        }

        @Override public Optional<WhitelistRequest> findById(UUID id) { return delegate.findById(id); }
        @Override public Optional<WhitelistRequest> findActiveByName(String name) { return delegate.findActiveByName(name); }
        @Override public Optional<WhitelistRequest> findLatestByName(String name) { return delegate.findLatestByName(name); }
        @Override public List<WhitelistRequest> findLatestByNameAndStatuses(String name, Set<RequestStatus> statuses, int limit) {
            return delegate.findLatestByNameAndStatuses(name, statuses, limit);
        }
        @Override public List<WhitelistRequest> findByIdPrefix(String prefix) { return delegate.findByIdPrefix(prefix); }
        @Override public List<WhitelistRequest> findByStatus(Optional<RequestStatus> status, int limit) {
            return delegate.findByStatus(status, limit);
        }
        @Override public void savePublication(UUID id, PublicationRef ref, Instant now) { delegate.savePublication(id, ref, now); }
        @Override public List<PublicationRef> publications(UUID id) { return delegate.publications(id); }
        @Override public DecisionClaim claimApproval(UUID id, AdminPrincipal actor, String reason, Instant now, UUID token) {
            return delegate.claimApproval(id, actor, reason, now, token);
        }
        @Override public DecisionResultSnapshot resolveTerminal(UUID id, DecisionAction action, AdminPrincipal actor,
                                                               String reason, Instant now) {
            return delegate.resolveTerminal(id, action, actor, reason, now);
        }
        @Override public boolean finalizeApproval(UUID id, UUID token, AdminPrincipal actor, String reason, Instant now) {
            return delegate.finalizeApproval(id, token, actor, reason, now);
        }
        @Override public boolean resetApproval(UUID id, UUID token, AdminPrincipal actor, String error, Instant now) {
            return delegate.resetApproval(id, token, actor, error, now);
        }
        @Override public List<WhitelistRequest> findResolvingApprovals() { return delegate.findResolvingApprovals(); }
        @Override public List<WhitelistRequest> findResolvingUndos() { return delegate.findResolvingUndos(); }
        @Override public DecisionResultSnapshot reopen(UUID id, AdminPrincipal actor, String reason, Instant now) {
            return delegate.reopen(id, actor, reason, now);
        }
        @Override public DecisionResultSnapshot claimUndo(UUID id, AdminPrincipal actor, String reason, Instant now, UUID token) {
            return delegate.claimUndo(id, actor, reason, now, token);
        }
        @Override public boolean resetUndo(UUID id, UUID token, AdminPrincipal actor, String error, Instant now) {
            return delegate.resetUndo(id, token, actor, error, now);
        }
        @Override public DecisionResultSnapshot finalizeUndo(UUID id, UUID token, AdminPrincipal actor, String reason, Instant now) {
            return delegate.finalizeUndo(id, token, actor, reason, now);
        }
        @Override public boolean unblock(String name, AdminPrincipal actor, String reason, Instant now) {
            return delegate.unblock(name, actor, reason, now);
        }
        @Override public boolean isBlocked(String name) { return delegate.isBlocked(name); }
        @Override public List<OutboxEvent> readyOutbox(Instant now, int limit) { return delegate.readyOutbox(now, limit); }
        @Override public void completeOutbox(UUID id, Instant now) { delegate.completeOutbox(id, now); }
        @Override public void retryOutbox(UUID id, Instant next, String error, Instant now) { delegate.retryOutbox(id, next, error, now); }
        @Override public void deferOutbox(UUID id, Instant next, Instant now) { delegate.deferOutbox(id, next, now); }
        @Override public long pendingOutboxCount() { return delegate.pendingOutboxCount(); }
        @Override public void close() throws Exception { delegate.close(); }
    }
}
