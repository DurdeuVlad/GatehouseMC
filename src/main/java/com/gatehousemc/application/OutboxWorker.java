package com.gatehousemc.application;

import com.gatehousemc.domain.OutboxEvent;
import com.gatehousemc.domain.RequestView;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.port.ClockPort;
import com.gatehousemc.port.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class OutboxWorker implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxWorker.class);
    private final WorkflowRepository repository;
    private final ApprovalInterfaceRouter router;
    private final ClockPort clock;
    private final ScheduledExecutorService executor;
    private final ConcurrentMap<UUID, FailureTracker> failureTrackers = new ConcurrentHashMap<>();

    static final class FailureTracker {
        String signature;
        final Instant firstFailureTime;
        Instant lastWarnTime;
        int consecutiveFailures;

        FailureTracker(String signature, Instant now) {
            this.signature = signature;
            this.firstFailureTime = now;
            this.lastWarnTime = now;
            this.consecutiveFailures = 1;
        }

        int consecutiveFailures() {
            return consecutiveFailures;
        }
    }

    public OutboxWorker(WorkflowRepository repository, ApprovalInterfaceRouter router, ClockPort clock) {
        this.repository = repository;
        this.router = router;
        this.clock = clock;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "whitelistrequest-outbox");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::drain, 0, 500, TimeUnit.MILLISECONDS);
    }

    public void drain() {
        List<OutboxEvent> events;
        try {
            events = repository.readyOutbox(clock.now(), 25);
        } catch (RuntimeException error) {
            LOGGER.warn("outbox.poll_failed errorType={}", error.getClass().getSimpleName());
            return;
        }
        for (OutboxEvent event : events) {
            try {
                process(event);
            } catch (RuntimeException error) {
                retry(event, error);
            }
        }
    }

    private void process(OutboxEvent event) {
        WhitelistRequest request = repository.findById(event.aggregateId()).orElse(null);
        if (request == null) {
            failureTrackers.remove(event.id());
            repository.completeOutbox(event.id(), clock.now());
            return;
        }
        switch (event.eventType()) {
            case "REQUEST_CREATED" -> processCreated(event, request);
            case "REQUEST_RESOLVED", "REQUEST_UPDATED" -> processUpdate(event, request);
            default -> {
                failureTrackers.remove(event.id());
                repository.completeOutbox(event.id(), clock.now());
            }
        }
    }

    private void processCreated(OutboxEvent event, WhitelistRequest request) {
        List<com.gatehousemc.domain.PublicationRef> existing = repository.publications(request.id());
        router.publish(RequestView.from(request), existing).whenComplete((publications, error) -> {
            Throwable failure = error;
            try {
                saveSuccessfulPublications(request.id(), publications, error);
            } catch (RuntimeException storageError) {
                failure = storageError;
            }
            finish(event, failure);
        });
    }

    private void processUpdate(OutboxEvent event, WhitelistRequest request) {
        router.updateAll(repository.publications(request.id()), RequestView.from(request))
                .whenComplete((ignored, error) -> finish(event, error));
    }

    private void saveSuccessfulPublications(UUID requestId, List<com.gatehousemc.domain.PublicationRef> publications, Throwable error) {
        if (publications != null) {
            publications.forEach(publication -> repository.savePublication(requestId, publication, clock.now()));
        }
        FanoutPublishException partial = findCause(error, FanoutPublishException.class);
        if (partial != null) {
            partial.successfulPublications().forEach(publication -> repository.savePublication(requestId, publication, clock.now()));
        }
    }

    private void retry(OutboxEvent event, Throwable error) {
        logPublishFailure(event, error);
        try {
            repository.retryOutbox(event.id(), nextAttempt(event), safeMessage(error), clock.now());
        } catch (RuntimeException storageError) {
            LOGGER.warn("outbox.persistence.failed operation=retry errorType={}",
                    storageError.getClass().getSimpleName());
        }
    }

    private void finish(OutboxEvent event, Throwable error) {
        try {
            if (error == null) {
                failureTrackers.remove(event.id());
                repository.completeOutbox(event.id(), clock.now());
            } else {
                logPublishFailure(event, error);
                repository.retryOutbox(event.id(), nextAttempt(event), safeMessage(error), clock.now());
            }
        } catch (RuntimeException storageError) {
            LOGGER.warn("outbox.persistence.failed operation=finish errorType={}",
                    storageError.getClass().getSimpleName());
        }
    }

    private void logPublishFailure(OutboxEvent event, Throwable error) {
        Throwable root = rootCause(error);
        String errorType = root.getClass().getSimpleName();
        String errorMessage = root.getMessage() != null ? root.getMessage() : "";
        String failureSignature = errorType + ": " + errorMessage;
        Instant now = clock.now();

        FailureTracker tracker = failureTrackers.get(event.id());
        if (tracker == null) {
            if (failureTrackers.size() > 1000) {
                failureTrackers.clear();
            }
            failureTrackers.put(event.id(), new FailureTracker(failureSignature, now));
            LOGGER.warn("outbox.publish_failed eventId={} errorType={} message=\"{}\"",
                    event.id(), errorType, errorMessage);
            return;
        }

        boolean causeChanged = !Objects.equals(tracker.signature, failureSignature);
        if (causeChanged) {
            tracker.signature = failureSignature;
            tracker.lastWarnTime = now;
            tracker.consecutiveFailures = 1;
            LOGGER.warn("outbox.publish_failed eventId={} errorType={} message=\"{}\" (failure reason changed)",
                    event.id(), errorType, errorMessage);
            return;
        }

        tracker.consecutiveFailures++;
        boolean thresholdReached = (tracker.consecutiveFailures % 10 == 0)
                || Duration.between(tracker.lastWarnTime, now).compareTo(Duration.ofHours(1)) >= 0;

        if (thresholdReached) {
            tracker.lastWarnTime = now;
            LOGGER.warn("outbox.publish_failed eventId={} attempts={} errorType={} message=\"{}\" (still failing)",
                    event.id(), event.attempts() + 1, errorType, errorMessage);
        } else {
            LOGGER.debug("outbox.publish_retry_failed eventId={} attempts={} errorType={} message=\"{}\"",
                    event.id(), event.attempts() + 1, errorType, errorMessage);
        }
    }

    private Instant nextAttempt(OutboxEvent event) {
        long seconds = Math.min(300, 1L << Math.min(event.attempts(), 8));
        return clock.now().plus(Duration.ofSeconds(seconds));
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "";
        Throwable root = rootCause(error);
        String type = root.getClass().getSimpleName();
        String msg = root.getMessage();
        if (msg == null || msg.isBlank()) return type;
        return type + ": " + (msg.length() > 255 ? msg.substring(0, 255) : msg);
    }

    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause;
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    FailureTracker failureTracker(UUID eventId) {
        return failureTrackers.get(eventId);
    }

    void handleFinish(OutboxEvent event, Throwable error) {
        finish(event, error);
    }

    @Override
    public void close() {
        failureTrackers.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
