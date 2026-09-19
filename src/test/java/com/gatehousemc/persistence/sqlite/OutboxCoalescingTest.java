package com.gatehousemc.persistence.sqlite;

import com.gatehousemc.domain.AdminPrincipal;
import com.gatehousemc.domain.DecisionAction;
import com.gatehousemc.domain.OutboxEvent;
import com.gatehousemc.domain.PlayerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutboxCoalescingTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void keepsAtMostOneReadyAttemptUpdate(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("coalesce.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            PlayerIdentity identity = PlayerIdentity.of("CoalescePlayer");
            repository.recordAttempt(identity, NOW, Duration.ZERO);
            completeCreated(repository);

            repository.recordAttempt(identity, NOW.plusSeconds(1), Duration.ZERO);
            repository.recordAttempt(identity, NOW.plusSeconds(2), Duration.ZERO);
            repository.recordAttempt(identity, NOW.plusSeconds(3), Duration.ZERO);

            List<OutboxEvent> ready = repository.readyOutbox(NOW.plusSeconds(3), 25);
            assertEquals(1, ready.size());
            assertEquals("REQUEST_ATTEMPT_UPDATED", ready.get(0).eventType());
        }
    }

    @Test
    void allowsOneReadyFollowUpWhileAnAttemptUpdateIsProcessing(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("processing.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            PlayerIdentity identity = PlayerIdentity.of("ProcessingPlayer");
            repository.recordAttempt(identity, NOW, Duration.ZERO);
            completeCreated(repository);
            repository.recordAttempt(identity, NOW.plusSeconds(1), Duration.ZERO);
            assertEquals(1, repository.readyOutbox(NOW.plusSeconds(1), 25).size());

            repository.recordAttempt(identity, NOW.plusSeconds(2), Duration.ZERO);
            repository.recordAttempt(identity, NOW.plusSeconds(3), Duration.ZERO);
            List<OutboxEvent> followUp = repository.readyOutbox(NOW.plusSeconds(3), 25);
            assertEquals(1, followUp.size());
            assertEquals("REQUEST_ATTEMPT_UPDATED", followUp.get(0).eventType());
        }
    }

    @Test
    void terminalUpdateSupersedesObsoleteAttemptUpdates(@TempDir Path temp) throws Exception {
        try (SqliteDatabase database = new SqliteDatabase(temp.resolve("terminal.sqlite"), 5000);
             SqliteWorkflowRepository repository = new SqliteWorkflowRepository(database)) {
            PlayerIdentity identity = PlayerIdentity.of("TerminalPlayer");
            repository.recordAttempt(identity, NOW, Duration.ZERO);
            completeCreated(repository);
            repository.recordAttempt(identity, NOW.plusSeconds(1), Duration.ZERO);
            var request = repository.findActiveByName(identity.normalizedUsername()).orElseThrow();
            repository.resolveTerminal(request.id(), DecisionAction.DENY, AdminPrincipal.console(),
                    "test", NOW.plusSeconds(2));

            List<OutboxEvent> ready = repository.readyOutbox(NOW.plusSeconds(2), 25);
            assertEquals(1, ready.size());
            assertEquals("REQUEST_RESOLVED", ready.get(0).eventType());
            assertTrue(ready.stream().noneMatch(event -> event.eventType().equals("REQUEST_ATTEMPT_UPDATED")));
        }
    }

    private static void completeCreated(SqliteWorkflowRepository repository) {
        OutboxEvent created = repository.readyOutbox(NOW, 25).get(0);
        repository.completeOutbox(created.id(), NOW);
    }
}
