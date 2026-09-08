package com.gatehousemc.whitelistrequest.domain;

import java.util.Objects;

public enum RequestStatus {
    PENDING,
    RESOLVING,
    APPROVED,
    DENIED,
    BLOCKED;

    public boolean isActive() {
        return this == PENDING || this == RESOLVING;
    }

    public boolean isTerminal() {
        return !isActive();
    }

    public boolean canTransitionTo(RequestStatus target) {
        return switch (this) {
            case PENDING -> target == RESOLVING || target == DENIED || target == BLOCKED;
            case RESOLVING -> target == PENDING || target == APPROVED;
            case APPROVED, DENIED, BLOCKED -> false;
        };
    }

    public static void requireTransition(RequestStatus from, RequestStatus to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (!from.canTransitionTo(to)) {
            throw new IllegalStateException("Illegal request transition: " + from + " -> " + to);
        }
    }
}
