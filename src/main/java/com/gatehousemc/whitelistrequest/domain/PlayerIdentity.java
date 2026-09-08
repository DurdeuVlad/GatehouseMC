package com.gatehousemc.whitelistrequest.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** The exact offline profile observed by Minecraft plus its workflow key. */
public record PlayerIdentity(UUID offlineUuid, String exactUsername, String normalizedUsername) {
    public PlayerIdentity {
        Objects.requireNonNull(offlineUuid, "offlineUuid");
        Objects.requireNonNull(exactUsername, "exactUsername");
        Objects.requireNonNull(normalizedUsername, "normalizedUsername");
        if (exactUsername.isBlank() || exactUsername.length() > 16) {
            throw new IllegalArgumentException("Username must contain 1-16 characters");
        }
        String expected = exactUsername.toLowerCase(Locale.ROOT);
        if (!expected.equals(normalizedUsername)) {
            throw new IllegalArgumentException("normalizedUsername must be Locale.ROOT lowercase");
        }
    }

    public static PlayerIdentity of(UUID offlineUuid, String exactUsername) {
        Objects.requireNonNull(exactUsername, "exactUsername");
        return new PlayerIdentity(offlineUuid, exactUsername, exactUsername.toLowerCase(Locale.ROOT));
    }
}
