package com.gatehousemc.whitelistrequest.domain;

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
}
