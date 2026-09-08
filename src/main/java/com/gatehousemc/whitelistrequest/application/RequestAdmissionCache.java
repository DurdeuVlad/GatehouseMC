package com.gatehousemc.whitelistrequest.application;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

public final class RequestAdmissionCache {
    private final ConcurrentHashMap<String, AdmissionState> states = new ConcurrentHashMap<>();
    private volatile boolean degraded;

    public AdmissionState get(String normalizedUsername) {
        AdmissionState state = states.getOrDefault(normalizedUsername, AdmissionState.unknown());
        if (state.kind() == AdmissionState.Kind.DENIED && state.until().isAfter(Instant.now(Clock.systemUTC()))) {
            return state;
        }
        if (state.kind() == AdmissionState.Kind.DENIED) {
            states.remove(normalizedUsername, state);
            return AdmissionState.unknown();
        }
        return state;
    }

    public void put(String normalizedUsername, AdmissionState state) {
        states.put(normalizedUsername, state);
    }

    public void markDegraded(boolean value) {
        degraded = value;
    }

    public boolean isDegraded() {
        return degraded;
    }
}
