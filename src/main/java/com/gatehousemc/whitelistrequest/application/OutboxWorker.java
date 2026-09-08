package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.OutboxEvent;
import com.gatehousemc.whitelistrequest.domain.RequestView;
import com.gatehousemc.whitelistrequest.domain.WhitelistRequest;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class OutboxWorker implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxWorker.class);
    private final WorkflowRepository repository;
    private final ApprovalInterfaceRouter router;
    private final ClockPort clock;
    private final ScheduledExecutorService executor;

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
            repository.completeOutbox(event.id(), clock.now());
            return;
        }
        if (event.eventType().equals("REQUEST_CREATED")) {
            List<com.gatehousemc.whitelistrequest.domain.PublicationRef> existing = repository.publications(request.id());
            router.publish(RequestView.from(request), existing).whenComplete((publications, error) -> {
                Throwable failure = error;
                try {
                    saveSuccessfulPublications(request.id(), publications, error);
                } catch (RuntimeException storageError) {
                    failure = storageError;
                }
                finish(event, failure);
            });
            return;
        } else if (event.eventType().equals("REQUEST_RESOLVED") || event.eventType().equals("REQUEST_UPDATED")) {
            router.updateAll(repository.publications(request.id()), RequestView.from(request)).whenComplete((ignored, error) -> finish(event, error));
            return;
        } else {
            repository.completeOutbox(event.id(), clock.now());
        }
    }

    private void saveSuccessfulPublications(UUID requestId, List<com.gatehousemc.whitelistrequest.domain.PublicationRef> publications, Throwable error) {
        if (publications != null) {
            publications.forEach(publication -> repository.savePublication(requestId, publication, clock.now()));
        }
        FanoutPublishException partial = findCause(error, FanoutPublishException.class);
        if (partial != null) {
            partial.successfulPublications().forEach(publication -> repository.savePublication(requestId, publication, clock.now()));
        }
    }

    private void retry(OutboxEvent event, Throwable error) {
        try {
            repository.retryOutbox(event.id(), nextAttempt(event), safeMessage(error), clock.now());
        } catch (RuntimeException storageError) {
            LOGGER.warn("outbox.persistence.failed operation=retry errorType={}",
                    storageError.getClass().getSimpleName());
        }
    }

    private void finish(OutboxEvent event, Throwable error) {
        try {
            if (error == null) repository.completeOutbox(event.id(), clock.now());
            else repository.retryOutbox(event.id(), nextAttempt(event), safeMessage(error), clock.now());
        } catch (RuntimeException storageError) {
            LOGGER.warn("outbox.persistence.failed operation=finish errorType={}",
                    storageError.getClass().getSimpleName());
        }
    }

    private Instant nextAttempt(OutboxEvent event) {
        long seconds = Math.min(300, 1L << Math.min(event.attempts(), 8));
        return clock.now().plus(Duration.ofSeconds(seconds));
    }

    private static String safeMessage(Throwable error) {
        if (error == null) return "";
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getClass().getSimpleName();
    }

    private static <T extends Throwable> T findCause(Throwable error, Class<T> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return type.cast(current);
            current = current.getCause();
        }
        return null;
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
