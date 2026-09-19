package com.gatehousemc.application;

import com.gatehousemc.domain.AttemptOutcome;
import com.gatehousemc.domain.AttemptState;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.port.ClockPort;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Loader-neutral admission worker with per-name coalescing and admission controls. */
public final class AdmissionWorker implements AutoCloseable {
    public enum OfferStatus { ACCEPTED, COALESCED, THROTTLED, CAPACITY, DEGRADED }
    public record OfferResult(OfferStatus status) {}

    private final ArrayBlockingQueue<WorkUnit> queue;
    private final WhitelistRequestService service;
    private final ClockPort clock;
    private final ActiveRequestCounter activeRequests;
    private final TokenBucket newRequestLimiter;
    private final int maxPendingRequests;
    private final int maxCoalescingEntries;
    private final Duration coalesceWindow;
    private final ExecutorService executor;
    private final Map<String, WorkUnit> byUsername = new HashMap<>();
    private final AtomicLong coalescedAttempts = new AtomicLong();
    private final Object lock = new Object();
    private volatile boolean running;
    private volatile boolean closed;

    public AdmissionWorker(int capacity, WhitelistRequestService service) {
        this(capacity, service, Instant::now, new ActiveRequestCounter(0), 30, 10, 500, 5);
    }

    public AdmissionWorker(int capacity, WhitelistRequestService service, ClockPort clock,
                           ActiveRequestCounter activeRequests, int newRequestRatePerMinute,
                           int newRequestBurst, int maxPendingRequests) {
        this(capacity, service, clock, activeRequests, newRequestRatePerMinute, newRequestBurst,
                maxPendingRequests, 5);
    }

    public AdmissionWorker(int capacity, WhitelistRequestService service, ClockPort clock,
                           ActiveRequestCounter activeRequests, int newRequestRatePerMinute,
                           int newRequestBurst, int maxPendingRequests, int attemptCoalesceSeconds) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        if (attemptCoalesceSeconds < 1) throw new IllegalArgumentException("attemptCoalesceSeconds must be positive");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.service = Objects.requireNonNull(service, "service");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeRequests = Objects.requireNonNull(activeRequests, "activeRequests");
        this.newRequestLimiter = new TokenBucket(newRequestRatePerMinute, newRequestBurst, clock);
        this.maxPendingRequests = maxPendingRequests;
        this.maxCoalescingEntries = Math.max(1, Math.min(capacity, 2000));
        this.coalesceWindow = Duration.ofSeconds(attemptCoalesceSeconds);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gatehousemc-persistence");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        synchronized (lock) {
            if (closed || running) return;
            running = true;
        }
        executor.submit(() -> {
            while (running || !queue.isEmpty()) {
                try {
                    WorkUnit unit = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (unit != null) process(unit);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException ignored) {
                    service.cache().markDegraded(true);
                }
            }
        });
    }

    public OfferResult offer(PlayerIdentity identity, AdmissionState.Kind knownState) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(knownState, "knownState");
        synchronized (lock) {
            if (closed || service.cache().isDegraded() || knownState == AdmissionState.Kind.DEGRADED) {
                return new OfferResult(OfferStatus.DEGRADED);
            }
            Instant now = clock.now();
            purgeCompletedEntries(now);
            String normalized = identity.normalizedUsername();
            WorkUnit existing = byUsername.get(normalized);
            if (existing != null && !existing.expired(clock.now(), coalesceWindow)) {
                existing.addAttempt(clock.now());
                coalescedAttempts.incrementAndGet();
                if (!existing.queued && !existing.processing) {
                    if (!queue.offer(existing)) return new OfferResult(OfferStatus.CAPACITY);
                    existing.queued = true;
                }
                return new OfferResult(OfferStatus.COALESCED);
            }
            if (existing != null) byUsername.remove(normalized);
            if (byUsername.size() >= maxCoalescingEntries && !evictCompletedEntry(now)) {
                return new OfferResult(OfferStatus.CAPACITY);
            }
            boolean reserved = false;
            if (knownState == AdmissionState.Kind.UNKNOWN) {
                if (!activeRequests.tryReserve(maxPendingRequests)) return new OfferResult(OfferStatus.CAPACITY);
                reserved = true;
                if (!newRequestLimiter.tryAcquire()) {
                    activeRequests.decrement();
                    return new OfferResult(OfferStatus.THROTTLED);
                }
            }
            WorkUnit unit = new WorkUnit(identity, reserved, clock.now());
            if (!queue.offer(unit)) {
                if (reserved) activeRequests.decrement();
                return new OfferResult(OfferStatus.CAPACITY);
            }
            unit.queued = true;
            byUsername.put(normalized, unit);
            return new OfferResult(OfferStatus.ACCEPTED);
        }
    }

    public boolean offer(PlayerIdentity identity) {
        return offer(identity, service.cache().get(identity.normalizedUsername()).kind()).status() == OfferStatus.ACCEPTED;
    }

    private void process(WorkUnit unit) {
        synchronized (lock) {
            unit.queued = false;
            unit.processing = true;
        }
        long delta = unit.attempts.getAndSet(0);
        Instant observedAt = unit.latestAttempt;
        AttemptOutcome outcome;
        try {
            outcome = service.recordAttempt(unit.identity, delta, observedAt);
        } catch (RuntimeException error) {
            service.cache().markDegraded(true);
            outcome = AttemptOutcome.of(AttemptState.DEGRADED, null);
        }
        synchronized (lock) {
            unit.processing = false;
            unit.lastCompletedAt = clock.now();
            if (unit.reservationHeld && outcome.state() != AttemptState.CREATED) {
                activeRequests.decrement();
                unit.reservationHeld = false;
            }
            if (unit.attempts.get() > 0 && !closed) {
                if (queue.offer(unit)) unit.queued = true;
                else {
                    byUsername.remove(unit.identity.normalizedUsername());
                    if (unit.reservationHeld) activeRequests.decrement();
                    service.cache().markDegraded(true);
                }
            }
        }
    }

    public int size() { return queue.size(); }

    public int availableNewRequestTokens() { return newRequestLimiter.availableTokens(); }

    public long coalescedAttempts() { return coalescedAttempts.get(); }

    public int pendingWorkUnits() {
        synchronized (lock) {
            int processing = 0;
            for (WorkUnit unit : byUsername.values()) if (unit.processing) processing++;
            return queue.size() + processing;
        }
    }

    /** Number of username work units retained for reconnect coalescing. */
    public int coalescingEntryCount() {
        synchronized (lock) {
            purgeCompletedEntries(clock.now());
            return byUsername.size();
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            running = false;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class WorkUnit {
        private final PlayerIdentity identity;
        private final AtomicLong attempts = new AtomicLong(1);
        private volatile Instant latestAttempt;
        private volatile Instant lastCompletedAt;
        private volatile boolean queued;
        private volatile boolean processing;
        private boolean reservationHeld;

        private WorkUnit(PlayerIdentity identity, boolean reservationHeld, Instant now) {
            this.identity = identity;
            this.reservationHeld = reservationHeld;
            this.latestAttempt = now;
        }

        private void addAttempt(Instant now) {
            attempts.incrementAndGet();
            latestAttempt = now;
        }

        private boolean expired(Instant now, Duration window) {
            return lastCompletedAt != null && !now.isBefore(lastCompletedAt.plus(window));
        }

        private boolean evictable(Instant now, Duration window) {
            return !queued && !processing && expired(now, window);
        }
    }

    private void purgeCompletedEntries(Instant now) {
        Iterator<Map.Entry<String, WorkUnit>> iterator = byUsername.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().evictable(now, coalesceWindow)) iterator.remove();
        }
    }

    private boolean evictCompletedEntry(Instant now) {
        Iterator<Map.Entry<String, WorkUnit>> iterator = byUsername.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().evictable(now, coalesceWindow)) {
                iterator.remove();
                return true;
            }
        }
        return false;
    }
}
