package com.gatehousemc.whitelistrequest.application;

import com.gatehousemc.whitelistrequest.port.ClockPort;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

public final class RequestAdmissionCache {
    private final ConcurrentHashMap<String, AdmissionState> states = new ConcurrentHashMap<>();
    private final ClockPort clock;
    private volatile boolean degraded;

    public RequestAdmissionCache() {
        this(Instant::now);
    }

    public RequestAdmissionCache(ClockPort clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AdmissionState get(String normalizedUsername) {
        AtomicReference<AdmissionState> result = new AtomicReference<>(AdmissionState.unknown());
        states.compute(normalizedUsername, (key, state) -> {
            if (state == null) return null;
            if (state.kind() == AdmissionState.Kind.DENIED && !state.until().isAfter(clock.now())) {
                return null;
            }
            result.set(state);
            return state;
        });
        return result.get();
    }

    public void put(String normalizedUsername, AdmissionState state) {
        states.put(normalizedUsername, state);
    }

    public void invalidate(String normalizedUsername) {
        states.remove(normalizedUsername);
    }

    public void markDegraded(boolean value) {
        degraded = value;
    }

    public boolean isDegraded() {
        return degraded;
    }
}
