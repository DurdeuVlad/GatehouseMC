package com.gatehousemc.domain;

import java.util.Optional;
import java.util.UUID;

public record DecisionClaim(ClaimOutcome outcome, Optional<WhitelistRequest> request, UUID token) {
    public enum ClaimOutcome { CLAIMED, NOT_FOUND, ALREADY_RESOLVED, ALREADY_RESOLVING }

    public DecisionClaim {
        if (outcome == null || request == null || token == null) throw new NullPointerException();
    }
}
