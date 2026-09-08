package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.domain.AttemptOutcome;
import com.gatehousemc.whitelistrequest.domain.AttemptState;
import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import com.gatehousemc.whitelistrequest.port.ClockPort;
import com.gatehousemc.whitelistrequest.port.WorkflowRepository;

import java.time.Duration;
import java.util.Objects;

public final class WhitelistRequestService {
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
}
