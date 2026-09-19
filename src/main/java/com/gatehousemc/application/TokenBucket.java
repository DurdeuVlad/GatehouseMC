package com.gatehousemc.application;

import com.gatehousemc.port.ClockPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Small synchronized token bucket for candidate new-request admission. */
final class TokenBucket {
    private final double refillPerSecond;
    private final double capacity;
    private final ClockPort clock;
    private double tokens;
    private Instant lastRefill;

    TokenBucket(int refillPerMinute, int burst, ClockPort clock) {
        if (refillPerMinute < 1 || burst < 1) throw new IllegalArgumentException("invalid token bucket configuration");
        this.refillPerSecond = refillPerMinute / 60.0;
        this.capacity = burst;
        this.tokens = burst;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.lastRefill = clock.now();
    }

    synchronized boolean tryAcquire() {
        refill();
        if (tokens < 1.0) return false;
        tokens -= 1.0;
        return true;
    }

    synchronized int availableTokens() {
        refill();
        return (int) Math.floor(tokens);
    }

    private void refill() {
        Instant now = clock.now();
        long nanos = Duration.between(lastRefill, now).toNanos();
        if (nanos > 0) {
            tokens = Math.min(capacity, tokens + (nanos / 1_000_000_000.0) * refillPerSecond);
            lastRefill = now;
        }
    }
}
