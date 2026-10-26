package com.gatehousemc.application;

import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.persistence.sqlite.SqliteDatabase;
import com.gatehousemc.persistence.sqlite.SqliteWorkflowRepository;
import com.gatehousemc.port.ClockPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdmissionWorkerTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void coalescesRapidAttemptsAndPersistsTheWholeBatch(@TempDir Path temp) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("admission.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(clock);
            WhitelistRequestService requests = new WhitelistRequestService(repository, clock, Duration.ZERO, cache);
            AdmissionWorker worker = new AdmissionWorker(256, requests, clock,
                    new ActiveRequestCounter(0), 30, 10, 500);
            worker.start();
            try {
                PlayerIdentity identity = PlayerIdentity.of("BurstPlayer");
                AdmissionWorker.OfferResult first = worker.offer(identity, AdmissionState.Kind.UNKNOWN);
                for (int i = 1; i < 100; i++) {
                    assertEquals(AdmissionWorker.OfferStatus.COALESCED,
                            worker.offer(identity, AdmissionState.Kind.UNKNOWN).status());
                }
                assertEquals(AdmissionWorker.OfferStatus.ACCEPTED, first.status());

                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (System.nanoTime() < deadline && repository.findLatestByName("burstplayer")
                        .map(request -> request.attemptCount() < 100).orElse(true)) {
                    Thread.sleep(10);
                }
                assertEquals(100, repository.findLatestByName("burstplayer").orElseThrow().attemptCount());
                assertEquals(0, worker.pendingWorkUnits());
            } finally {
                worker.close();
            }
        }
    }

    @Test
    void limitsOnlyUnknownCandidatesAndKnownStatesBypassTheBucket(@TempDir Path temp) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("limiter.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(clock);
            WhitelistRequestService requests = new WhitelistRequestService(repository, clock, Duration.ZERO, cache);
            AdmissionWorker worker = new AdmissionWorker(256, requests, clock,
                    new ActiveRequestCounter(0), 30, 2, 500);
            try {
                assertEquals(AdmissionWorker.OfferStatus.ACCEPTED,
                        worker.offer(PlayerIdentity.of("LimitOne"), AdmissionState.Kind.UNKNOWN).status());
                assertEquals(AdmissionWorker.OfferStatus.ACCEPTED,
                        worker.offer(PlayerIdentity.of("LimitTwo"), AdmissionState.Kind.UNKNOWN).status());
                assertEquals(AdmissionWorker.OfferStatus.THROTTLED,
                        worker.offer(PlayerIdentity.of("LimitThree"), AdmissionState.Kind.UNKNOWN).status());
                assertEquals(AdmissionWorker.OfferStatus.ACCEPTED,
                        worker.offer(PlayerIdentity.of("KnownPending"), AdmissionState.Kind.PENDING).status());
                assertTrue(worker.pendingWorkUnits() <= 3);
            } finally {
                worker.close();
            }
        }
    }

    @Test
    void coalescingIndexStaysBoundedWhenUniqueIdentitiesArrive(@TempDir Path temp) throws Exception {
        MutableClock clock = new MutableClock(NOW);
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("bounded.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            RequestAdmissionCache cache = new RequestAdmissionCache(clock);
            WhitelistRequestService requests = new WhitelistRequestService(repository, clock, Duration.ZERO, cache);
            AdmissionWorker worker = new AdmissionWorker(64, requests, clock,
                    new ActiveRequestCounter(0), 30, 10, 500);
            try {
                for (int i = 0; i < 10_000; i++) {
                    worker.offer(PlayerIdentity.of("User" + (i % 10_000)), AdmissionState.Kind.PENDING);
                }

                assertTrue(worker.coalescingEntryCount() <= 64);
            } finally {
                worker.close();
            }
        }
    }

    private static final class MutableClock implements ClockPort {
        private final AtomicReference<Instant> now;
        private MutableClock(Instant initial) { now = new AtomicReference<>(initial); }
        @Override public Instant now() { return now.get(); }
    }
}
