package com.gatehousemc.application;

import com.gatehousemc.application.admin.RequestAction;
import com.gatehousemc.domain.RequestStatus;
import com.gatehousemc.port.ClockPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Provider-neutral, single-use confirmation state. */
public final class ConfirmationStore {
    public static final Duration TTL = Duration.ofSeconds(60);
    public static final int MAX_PENDING = 4096;

    public record Confirmation(String token, String provider, String actorId, UUID requestId,
                               RequestAction action, RequestStatus expectedStatus,
                               Instant createdAt, Instant expiresAt) {}

    public enum ConsumeStatus {
        CONSUMED, MISSING, EXPIRED, WRONG_PROVIDER, WRONG_ACTOR, STALE, MISSING_REQUEST
    }

    public record ConsumeResult(ConsumeStatus status, Optional<Confirmation> confirmation) {
        static ConsumeResult of(ConsumeStatus status) {
            return new ConsumeResult(status, Optional.empty());
        }
    }

    private final ClockPort clock;
    private final ConcurrentMap<String, Confirmation> pending = new ConcurrentHashMap<>();

    public ConfirmationStore(ClockPort clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Confirmation issue(String provider, String actorId, UUID requestId,
                                           RequestAction action, RequestStatus expectedStatus) {
        Instant created = clock.now();
        purgeExpired(created);
        String token = UUID.randomUUID().toString().replace("-", "");
        Confirmation confirmation = new Confirmation(token, provider, actorId, requestId, action,
                expectedStatus, created, created.plus(TTL));
        if (pending.size() >= MAX_PENDING) evictOldest();
        pending.put(token, confirmation);
        return confirmation;
    }

    public int pendingCount() {
        purgeExpired(clock.now());
        return pending.size();
    }

    public Optional<Confirmation> find(String token) {
        Confirmation confirmation = pending.get(token);
        if (confirmation == null) return Optional.empty();
        if (!confirmation.expiresAt().isAfter(clock.now())) {
            pending.remove(token, confirmation);
            return Optional.empty();
        }
        return Optional.of(confirmation);
    }

    public ConsumeResult consume(String token, String provider, String actorId, RequestStatus currentStatus) {
        Confirmation confirmation = pending.get(token);
        if (confirmation == null) return ConsumeResult.of(ConsumeStatus.MISSING);
        Instant now = clock.now();
        if (!confirmation.expiresAt().isAfter(now)) {
            pending.remove(token, confirmation);
            return ConsumeResult.of(ConsumeStatus.EXPIRED);
        }
        if (!confirmation.provider().equals(provider)) return ConsumeResult.of(ConsumeStatus.WRONG_PROVIDER);
        if (!confirmation.actorId().equals(actorId)) return ConsumeResult.of(ConsumeStatus.WRONG_ACTOR);
        if (currentStatus == null) {
            pending.remove(token, confirmation);
            return new ConsumeResult(ConsumeStatus.MISSING_REQUEST, Optional.of(confirmation));
        }
        if (confirmation.expectedStatus() != currentStatus) {
            pending.remove(token, confirmation);
            return new ConsumeResult(ConsumeStatus.STALE, Optional.of(confirmation));
        }
        return pending.remove(token, confirmation)
                ? new ConsumeResult(ConsumeStatus.CONSUMED, Optional.of(confirmation))
                : ConsumeResult.of(ConsumeStatus.MISSING);
    }

    public ConsumeResult cancel(String token, String provider, String actorId) {
        Confirmation confirmation = pending.get(token);
        if (confirmation == null) return ConsumeResult.of(ConsumeStatus.MISSING);
        if (!confirmation.expiresAt().isAfter(clock.now())) {
            pending.remove(token, confirmation);
            return ConsumeResult.of(ConsumeStatus.EXPIRED);
        }
        if (!confirmation.provider().equals(provider)) return ConsumeResult.of(ConsumeStatus.WRONG_PROVIDER);
        if (!confirmation.actorId().equals(actorId)) return ConsumeResult.of(ConsumeStatus.WRONG_ACTOR);
        return pending.remove(token, confirmation)
                ? new ConsumeResult(ConsumeStatus.CONSUMED, Optional.of(confirmation))
                : ConsumeResult.of(ConsumeStatus.MISSING);
    }

    private void purgeExpired(Instant now) {
        pending.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void evictOldest() {
        pending.values().stream()
                .min(java.util.Comparator.comparing(Confirmation::createdAt))
                .ifPresent(oldest -> pending.remove(oldest.token(), oldest));
    }
}
