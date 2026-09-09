package com.gatehousemc.domain;

import java.time.Instant;
import java.util.Optional;

public record AttemptOutcome(AttemptState state, Optional<WhitelistRequest> request, Instant cooldownUntil) {
    public AttemptOutcome {
        if (state == null || request == null || cooldownUntil == null) throw new NullPointerException();
    }

    public static AttemptOutcome of(AttemptState state, WhitelistRequest request) {
        return new AttemptOutcome(state, Optional.ofNullable(request), Instant.MIN);
    }

    public static AttemptOutcome deniedUntil(Instant until, WhitelistRequest request) {
        return new AttemptOutcome(AttemptState.DENIED_COOLDOWN, Optional.ofNullable(request), until);
    }
}
