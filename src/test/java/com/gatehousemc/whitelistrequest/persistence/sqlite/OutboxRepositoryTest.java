package com.gatehousemc.whitelistrequest.persistence.sqlite;

import com.gatehousemc.whitelistrequest.domain.OutboxEvent;
import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxRepositoryTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void concurrentWorkersClaimAnOutboxEventOnlyOnce(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("outbox.sqlite");
        try (SqliteDatabase firstDatabase = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository first = new SqliteWorkflowRepository(firstDatabase);
             SqliteDatabase secondDatabase = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository second = new SqliteWorkflowRepository(secondDatabase)) {
            first.recordAttempt(PlayerIdentity.of("OutboxPlayer"), NOW, Duration.ofDays(1));
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CompletableFuture<List<OutboxEvent>> firstClaim = CompletableFuture.supplyAsync(() -> claim(first, ready, start));
            CompletableFuture<List<OutboxEvent>> secondClaim = CompletableFuture.supplyAsync(() -> claim(second, ready, start));
            ready.await();
            start.countDown();

            List<OutboxEvent> firstEvents = firstClaim.join();
            List<OutboxEvent> secondEvents = secondClaim.join();

            assertEquals(1, firstEvents.size() + secondEvents.size());
            assertTrue(firstEvents.isEmpty() || secondEvents.isEmpty());
        }
    }

    @Test
    void processingOutboxEventIsRequeuedAfterRepositoryReopen(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("restart.sqlite");
        UUID outboxId;
        try (SqliteDatabase database = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            repository.recordAttempt(PlayerIdentity.of("RestartPlayer"), NOW, Duration.ofDays(1));
            outboxId = repository.readyOutbox(NOW, 10).get(0).id();
        }
        try (SqliteDatabase database = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            assertEquals(outboxId, repository.readyOutbox(NOW, 10).get(0).id());
        }
    }

    @Test
    void failedOutboxEventWaitsForItsNextAttempt(@TempDir Path temp) throws Exception {
        Path path = temp.resolve("retry.sqlite");
        try (SqliteDatabase database = new SqliteDatabase(path, 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            repository.recordAttempt(PlayerIdentity.of("RetryPlayer"), NOW, Duration.ofDays(1));
            OutboxEvent event = repository.readyOutbox(NOW, 10).get(0);
            Instant nextAttempt = NOW.plusSeconds(60);
            repository.retryOutbox(event.id(), nextAttempt, "provider unavailable", NOW);

            assertTrue(repository.readyOutbox(NOW, 10).isEmpty());
            assertEquals(1, repository.readyOutbox(nextAttempt, 10).get(0).attempts());
        }
    }

    private static List<OutboxEvent> claim(SqliteWorkflowRepository repository, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await();
            return repository.readyOutbox(NOW, 10);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}
