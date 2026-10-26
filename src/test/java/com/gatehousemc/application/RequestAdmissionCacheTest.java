package com.gatehousemc.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestAdmissionCacheTest {
    @Test
    void attackerControlledUsernamesCannotGrowTheCacheWithoutBound() {
        RequestAdmissionCache cache = new RequestAdmissionCache(Instant::now, 64);

        for (int i = 0; i < 10_000; i++) {
            cache.put("player" + i, AdmissionState.deniedUntil(Instant.MAX));
        }

        assertTrue(cache.size() <= 64);
    }

    @Test
    void unknownStateRemovesTheEntry() {
        RequestAdmissionCache cache = new RequestAdmissionCache(Instant::now, 8);
        cache.put("player", AdmissionState.pending());
        cache.put("player", AdmissionState.unknown());

        assertEquals(0, cache.size());
        assertEquals(AdmissionState.Kind.UNKNOWN, cache.get("player").kind());
    }
}
