package com.gatehousemc.application;

import com.gatehousemc.port.ClockPort;

import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded fast-path admission cache. A reconnect can create at most one entry
 * per normalized username; retaining those entries forever would turn the
 * cache into an attacker-controlled heap allocation.
 */
public final class RequestAdmissionCache {
    public static final int DEFAULT_MAX_ENTRIES = 4096;
    private final Map<String, AdmissionState> states;
    private final ClockPort clock;
    private final int maxEntries;
    private volatile boolean degraded;

    public RequestAdmissionCache() {
        this(Instant::now);
    }

    public RequestAdmissionCache(ClockPort clock) {
        this(clock, DEFAULT_MAX_ENTRIES);
    }

    public RequestAdmissionCache(ClockPort clock, int maxEntries) {
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
        this.maxEntries = maxEntries;
        this.states = new LinkedHashMap<>(Math.min(maxEntries, 256), 0.75f, true);
    }

    public synchronized AdmissionState get(String normalizedUsername) {
        AdmissionState state = states.get(normalizedUsername);
        if (state == null) return AdmissionState.unknown();
        if (state.kind() == AdmissionState.Kind.DENIED && !state.until().isAfter(clock.now())) {
            states.remove(normalizedUsername);
            return AdmissionState.unknown();
        }
        return state;
    }

    public synchronized void put(String normalizedUsername, AdmissionState state) {
        Objects.requireNonNull(normalizedUsername, "normalizedUsername");
        Objects.requireNonNull(state, "state");
        if (state.kind() == AdmissionState.Kind.UNKNOWN) {
            states.remove(normalizedUsername);
            return;
        }
        purgeExpiredDenied();
        states.put(normalizedUsername, state);
        evictIfNeeded();
    }

    public synchronized void invalidate(String normalizedUsername) {
        states.remove(normalizedUsername);
    }

    public synchronized int size() {
        purgeExpiredDenied();
        return states.size();
    }

    public void markDegraded(boolean value) {
        degraded = value;
    }

    public boolean isDegraded() {
        return degraded;
    }

    private void purgeExpiredDenied() {
        Instant now = clock.now();
        states.entrySet().removeIf(entry -> entry.getValue().kind() == AdmissionState.Kind.DENIED
                && !entry.getValue().until().isAfter(now));
    }

    private void evictIfNeeded() {
        while (states.size() > maxEntries) {
            Iterator<Map.Entry<String, AdmissionState>> iterator = states.entrySet().iterator();
            Map.Entry<String, AdmissionState> fallback = null;
            while (iterator.hasNext()) {
                Map.Entry<String, AdmissionState> entry = iterator.next();
                if (entry.getValue().kind() != AdmissionState.Kind.PENDING
                        && entry.getValue().kind() != AdmissionState.Kind.BLOCKED) {
                    iterator.remove();
                    fallback = null;
                    break;
                }
                if (fallback == null) fallback = entry;
            }
            if (states.size() <= maxEntries) return;
            if (fallback != null) states.remove(fallback.getKey());
            else states.remove(states.keySet().iterator().next());
        }
    }
}
