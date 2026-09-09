package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.AttemptOutcome;
import com.gatehousemc.whitelistrequest.domain.AttemptState;
import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import com.gatehousemc.whitelistrequest.domain.RequestStatus;
import com.gatehousemc.whitelistrequest.domain.WhitelistRequest;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class WhitelistRequestService {
    private static final Logger LOGGER = LoggerFactory.getLogger(WhitelistRequestService.class);
    private final WorkflowRepository repository;
    private final ClockPort clock;
    private final Duration denialCooldown;
    private final RequestAdmissionCache cache;

    public WhitelistRequestService(WorkflowRepository repository, ClockPort clock, Duration denialCooldown,
                                   RequestAdmissionCache cache) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.denialCooldown = Objects.requireNonNull(denialCooldown, "denialCooldown");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    public AttemptOutcome recordAttempt(PlayerIdentity identity) {
        if (cache.isDegraded()) return AttemptOutcome.of(AttemptState.DEGRADED, null);
        AttemptOutcome outcome = repository.recordAttempt(identity, clock.now(), denialCooldown);
        switch (outcome.state()) {
            case CREATED, PENDING -> cache.put(identity.normalizedUsername(), AdmissionState.pending());
            case DENIED_COOLDOWN -> cache.put(identity.normalizedUsername(), AdmissionState.deniedUntil(outcome.cooldownUntil()));
            case BLOCKED -> cache.put(identity.normalizedUsername(), AdmissionState.blocked());
            case DEGRADED -> {
                cache.put(identity.normalizedUsername(), AdmissionState.degraded());
                cache.markDegraded(true);
            }
        }
        return outcome;
    }

    public RequestAdmissionCache cache() {
        return cache;
    }

    public void hydrateCache() {
        repository.findByStatus(Optional.of(RequestStatus.PENDING), 500)
                .forEach(request -> cache.put(request.identity().normalizedUsername(), AdmissionState.pending()));
        List<WhitelistRequest> blockedRequests = repository.findByStatus(Optional.of(RequestStatus.BLOCKED), 500);
        for (WhitelistRequest request : blockedRequests) {
            try {
                if (repository.isBlocked(request.identity().normalizedUsername())) {
                    cache.put(request.identity().normalizedUsername(), AdmissionState.blocked());
                }
            } catch (RuntimeException error) {
                LOGGER.warn("Failed to check block status for {} during cache hydration", request.identity().normalizedUsername(), error);
            }
        }
        repository.findByStatus(Optional.of(RequestStatus.DENIED), 500)
                .forEach(request -> {
                    Instant deniedUntil = request.resolvedAt().plus(denialCooldown);
                    if (deniedUntil.isAfter(clock.now())) {
                        cache.put(request.identity().normalizedUsername(), AdmissionState.deniedUntil(deniedUntil));
                    }
                });
    }
}
