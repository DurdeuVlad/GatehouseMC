package com.gatehousemc.application;

import com.gatehousemc.domain.*;
import com.gatehousemc.port.ApprovalInterface;
import com.gatehousemc.port.ProviderHealth;
import com.gatehousemc.port.RoutingMode;
import com.gatehousemc.port.WorkflowRepository;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OutboxWorkerTest {
    private static final Instant NOW = Instant.parse("2026-09-13T12:00:00Z");

    @Test
    @DisplayName("Regression test: a failing publish NEVER marks the outbox event complete, only schedules retry")
    void failingPublishNeverMarksOutboxComplete() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        OutboxEvent event = new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                OutboxEvent.OutboxState.PROCESSING, 0, NOW, NOW, NOW, null);

        AtomicBoolean completeCalled = new AtomicBoolean(false);
        AtomicBoolean retryCalled = new AtomicBoolean(false);

        WorkflowRepository repository = mock(WorkflowRepository.class, (proxy, method, args) -> {
            if (method.getName().equals("completeOutbox")) {
                completeCalled.set(true);
            } else if (method.getName().equals("retryOutbox")) {
                retryCalled.set(true);
                Instant nextAttempt = (Instant) args[1];
                String error = (String) args[2];
                assertTrue(nextAttempt.isAfter(NOW), "Next attempt should be in the future");
                assertTrue(error.contains("IllegalStateException"), "Error should record exception type");
            }
            return null;
        });

        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(), RoutingMode.PRIMARY_FALLBACK);
        OutboxWorker worker = new OutboxWorker(repository, router, () -> NOW);

        Throwable error = new IllegalStateException("Discord bot lacks permission to access channel 123");
        worker.handleFinish(event, error);

        assertFalse(completeCalled.get(), "Failing publish must NEVER call completeOutbox");
        assertTrue(retryCalled.get(), "Failing publish MUST call retryOutbox");
    }

    @Test
    @DisplayName("Proof 3: Transient failure eventually delivers and completes when destination becomes reachable")
    void transientFailureEventuallySucceedsAndCompletes() {
        UUID eventId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        WhitelistRequest request = new WhitelistRequest(requestId, PlayerIdentity.of("TransientPlayer"),
                RequestStatus.PENDING, NOW, NOW, NOW, NOW, 1, null, null, null, null, null);

        AtomicInteger completeCount = new AtomicInteger(0);
        AtomicInteger retryCount = new AtomicInteger(0);
        List<PublicationRef> savedPublications = new ArrayList<>();

        WorkflowRepository repository = mock(WorkflowRepository.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "findById" -> {
                    return Optional.of(request);
                }
                case "completeOutbox" -> {
                    completeCount.incrementAndGet();
                    return null;
                }
                case "retryOutbox" -> {
                    retryCount.incrementAndGet();
                    return null;
                }
                case "savePublication" -> {
                    savedPublications.add((PublicationRef) args[1]);
                    return null;
                }
                case "publications" -> {
                    return savedPublications;
                }
                default -> {
                    return null;
                }
            }
        });

        AtomicInteger publishAttempts = new AtomicInteger(0);
        ApprovalInterface flakyProvider = new ApprovalInterface() {
            @Override
            public String id() { return "discord"; }

            @Override
            public ProviderHealth health() { return ProviderHealth.HEALTHY; }

            @Override
            public void start() {}

            @Override
            public void stop() {}

            @Override
            public CompletionStage<PublicationRef> publish(RequestView req) {
                int attempt = publishAttempts.incrementAndGet();
                if (attempt < 3) {
                    // Attempts 1 and 2 fail (transient failure)
                    return CompletableFuture.failedFuture(new IllegalStateException("Discord channel temporarily unavailable (attempt " + attempt + ")"));
                }
                // Attempt 3 succeeds!
                return CompletableFuture.completedFuture(new PublicationRef("discord", "channel123", "msg456"));
            }

            @Override
            public CompletionStage<Void> update(PublicationRef pub, RequestView req) {
                return CompletableFuture.completedFuture(null);
            }
        };

        ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(flakyProvider), RoutingMode.PRIMARY_FALLBACK);
        OutboxWorker worker = new OutboxWorker(repository, router, () -> NOW);

        // Attempt 1: fails
        OutboxEvent event1 = new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                OutboxEvent.OutboxState.PROCESSING, 0, NOW, NOW, NOW, null);
        router.publish(RequestView.from(request), List.of())
                .whenComplete((pubs, err) -> worker.handleFinish(event1, err))
                .exceptionally(ex -> null)
                .toCompletableFuture().join();

        assertEquals(0, completeCount.get(), "First attempt must not complete outbox");
        assertEquals(1, retryCount.get(), "First attempt must schedule retry");
        assertNotNull(worker.failureTracker(eventId), "Failure tracker should be created on first failure");

        // Attempt 2: fails
        OutboxEvent event2 = new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                OutboxEvent.OutboxState.PROCESSING, 1, NOW.plusSeconds(2), NOW, NOW.plusSeconds(2), "IllegalStateException");
        router.publish(RequestView.from(request), List.of())
                .whenComplete((pubs, err) -> worker.handleFinish(event2, err))
                .exceptionally(ex -> null)
                .toCompletableFuture().join();

        assertEquals(0, completeCount.get(), "Second attempt must not complete outbox");
        assertEquals(2, retryCount.get(), "Second attempt must schedule retry");

        // Attempt 3: destination restored, publish succeeds!
        OutboxEvent event3 = new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                OutboxEvent.OutboxState.PROCESSING, 2, NOW.plusSeconds(6), NOW, NOW.plusSeconds(6), "IllegalStateException");
        router.publish(RequestView.from(request), List.of()).whenComplete((pubs, err) -> {
            if (pubs != null) {
                pubs.forEach(p -> repository.savePublication(requestId, p, NOW));
            }
            worker.handleFinish(event3, err);
        }).exceptionally(ex -> null).toCompletableFuture().join();

        assertEquals(1, completeCount.get(), "Third attempt must complete outbox upon success");
        assertEquals(2, retryCount.get(), "No additional retries scheduled on success");
        assertEquals(1, savedPublications.size(), "Publication must be saved upon successful delivery");
        assertEquals("msg456", savedPublications.get(0).messageId());
        assertNull(worker.failureTracker(eventId), "Failure tracker must be cleaned up on successful delivery");
    }

    @Test
    @DisplayName("Proof 4: Confirm log volume is reduced for persistent failure over 10 consecutive retry cycles (2 WARN vs 8 DEBUG)")
    void logVolumeReducedOverTenConsecutiveIdenticalFailures() {
        Logger logger = (Logger) LogManager.getLogger(OutboxWorker.class);
        TestLogAppender appender = new TestLogAppender();
        logger.addAppender(appender);
        Level origLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        try {
            UUID eventId = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();
            WorkflowRepository repository = mock(WorkflowRepository.class, (proxy, method, args) -> null);
            ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(), RoutingMode.PRIMARY_FALLBACK);
            OutboxWorker worker = new OutboxWorker(repository, router, () -> NOW);

            Throwable identicalError = new IllegalStateException("Discord bot found channel 'whitelist' (123) but lacks View Channel permission there");

            // Simulate 10 consecutive retry attempts with the exact same error
            for (int attempt = 0; attempt < 10; attempt++) {
                OutboxEvent event = new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                        OutboxEvent.OutboxState.PROCESSING, attempt, NOW.plusSeconds(attempt * 300), NOW, NOW, "IllegalStateException");
                worker.handleFinish(event, identicalError);
            }

            long warnCount = appender.events.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getMessage().getFormattedMessage().contains("outbox.publish_failed"))
                    .count();

            long debugCount = appender.events.stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .filter(e -> e.getMessage().getFormattedMessage().contains("outbox.publish_retry_failed"))
                    .count();

            // Attempt 1: WARN
            // Attempts 2..9: DEBUG (8 times)
            // Attempt 10: WARN (re-escalated at threshold)
            assertEquals(2, warnCount, "Expected exactly 2 WARN logs over 10 attempts (attempt 1 and attempt 10)");
            assertEquals(8, debugCount, "Expected exactly 8 DEBUG logs over 10 attempts (attempts 2 through 9)");

            // Verify first log was WARN
            LogEvent firstEvent = appender.events.get(0);
            assertEquals(Level.WARN, firstEvent.getLevel());
            assertTrue(firstEvent.getMessage().getFormattedMessage().contains("lacks View Channel permission there"));

            // Verify second log was DEBUG
            LogEvent secondEvent = appender.events.get(1);
            assertEquals(Level.DEBUG, secondEvent.getLevel());
            assertTrue(secondEvent.getMessage().getFormattedMessage().contains("attempts=2"));

            // Verify 10th log was re-escalated to WARN
            LogEvent tenthEvent = appender.events.get(9);
            assertEquals(Level.WARN, tenthEvent.getLevel());
            assertTrue(tenthEvent.getMessage().getFormattedMessage().contains("still failing"));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(origLevel);
            appender.stop();
        }
    }

    @Test
    @DisplayName("Failure reason change immediately re-escalates to WARN")
    void failureReasonChangeReEscalatesToWarn() {
        Logger logger = (Logger) LogManager.getLogger(OutboxWorker.class);
        TestLogAppender appender = new TestLogAppender();
        logger.addAppender(appender);
        Level origLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        try {
            UUID eventId = UUID.randomUUID();
            UUID requestId = UUID.randomUUID();
            WorkflowRepository repository = mock(WorkflowRepository.class, (proxy, method, args) -> null);
            ApprovalInterfaceRouter router = new ApprovalInterfaceRouter(List.of(), RoutingMode.PRIMARY_FALLBACK);
            OutboxWorker worker = new OutboxWorker(repository, router, () -> NOW);

            Throwable error1 = new IllegalStateException("Discord bot lacks View Channel permission");
            Throwable error2 = new IllegalStateException("Discord bot is not in the guild at all");

            // Attempt 1: error1 -> WARN
            worker.handleFinish(new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                    OutboxEvent.OutboxState.PROCESSING, 0, NOW, NOW, NOW, null), error1);

            // Attempt 2: error1 -> DEBUG
            worker.handleFinish(new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                    OutboxEvent.OutboxState.PROCESSING, 1, NOW, NOW, NOW, null), error1);

            // Attempt 3: error2 (changed) -> WARN!
            worker.handleFinish(new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                    OutboxEvent.OutboxState.PROCESSING, 2, NOW, NOW, NOW, null), error2);

            // Attempt 4: error2 -> DEBUG
            worker.handleFinish(new OutboxEvent(eventId, "REQUEST_CREATED", requestId, "{}",
                    OutboxEvent.OutboxState.PROCESSING, 3, NOW, NOW, NOW, null), error2);

            assertEquals(Level.WARN, appender.events.get(0).getLevel());
            assertEquals(Level.DEBUG, appender.events.get(1).getLevel());
            assertEquals(Level.WARN, appender.events.get(2).getLevel());
            assertTrue(appender.events.get(2).getMessage().getFormattedMessage().contains("failure reason changed"));
            assertEquals(Level.DEBUG, appender.events.get(3).getLevel());
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(origLevel);
            appender.stop();
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static class TestLogAppender extends AbstractAppender {
        final List<LogEvent> events = new ArrayList<>();

        TestLogAppender() {
            super("TestLogAppender", null, PatternLayout.createDefaultLayout(), false);
            start();
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
