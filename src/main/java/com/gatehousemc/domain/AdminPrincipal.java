package com.gatehousemc.domain;

import java.util.Objects;

public record AdminPrincipal(String provider, String externalId, String displayName) {
    public AdminPrincipal {
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("provider is required");
        if (externalId == null || externalId.isBlank()) throw new IllegalArgumentException("externalId is required");
        displayName = Objects.requireNonNullElse(displayName, externalId);
    }

    public static AdminPrincipal console() {
        return new AdminPrincipal("minecraft", "console", "Console");
    }
}
