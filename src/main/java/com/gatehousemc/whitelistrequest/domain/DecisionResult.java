package com.gatehousemc.whitelistrequest.domain;

import java.util.Optional;

public record DecisionResult(DecisionOutcome outcome, Optional<WhitelistRequest> request, String message) {
    public DecisionResult {
        if (outcome == null || request == null || message == null) throw new NullPointerException();
    }

    public static DecisionResult of(DecisionOutcome outcome, WhitelistRequest request, String message) {
        return new DecisionResult(outcome, Optional.ofNullable(request), message);
    }
}
