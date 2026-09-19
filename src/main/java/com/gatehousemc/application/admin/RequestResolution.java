package com.gatehousemc.application.admin;

import com.gatehousemc.domain.WhitelistRequest;

import java.util.Optional;

/** Result of request lookup; conflict and ambiguity are explicit, never guessed. */
public record RequestResolution(Outcome outcome, Optional<WhitelistRequest> request,
                                Optional<WhitelistRequest> activeConflict, String message) {
    public RequestResolution {
        if (outcome == null || request == null || activeConflict == null || message == null) {
            throw new NullPointerException();
        }
    }

    public enum Outcome {
        FOUND,
        NOT_FOUND,
        AMBIGUOUS,
        ACTIVE_CONFLICT,
        INVALID_STATE
    }
}
