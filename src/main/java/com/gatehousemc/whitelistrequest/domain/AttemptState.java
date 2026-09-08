package com.gatehousemc.whitelistrequest.domain;

public enum AttemptState {
    CREATED,
    PENDING,
    DENIED_COOLDOWN,
    BLOCKED,
    DEGRADED
}
