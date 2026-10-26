package com.gatehousemc.application;

import com.gatehousemc.port.ClockPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Fixed 1.2 per-provider actor limiter; business idempotency remains authoritative. */
public final class ExternalCommandRateLimiter {
    public static final int MAX_BUCKETS = 4096;
    public record Decision(boolean allowed, Duration retryAfter) {
        public static Decision allowAll() { return new Decision(true, Duration.ZERO); }
    }

    private record Key(String provider, String actor, boolean mutating) {}
    private final ClockPort clock;
    private final ConcurrentMap<Key, Bucket> buckets = new ConcurrentHashMap<>();
    private final Object bucketCreationLock = new Object();

    public ExternalCommandRateLimiter(ClockPort clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Decision allow(String provider, String actor, boolean mutating) {
        if (provider == null || actor == null) throw new NullPointerException("provider/actor");
        Instant now = clock.now();
        Key key = new Key(provider, actor, mutating);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            synchronized (bucketCreationLock) {
                bucket = buckets.get(key);
                if (bucket == null) {
                    evictStale(now);
                    if (buckets.size() >= MAX_BUCKETS) {
                        return new Decision(false, Duration.ofSeconds(1));
                    }
                    bucket = new Bucket(mutating ? 2.0 : 5.0, mutating ? 4.0 : 10.0, now);
                    buckets.put(key, bucket);
                }
            }
        }
        return bucket.tryAcquire(now);
    }

    public int bucketCount() { return buckets.size(); }

    private void evictStale(Instant now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().stale(now));
    }

    private static final class Bucket {
        private final double refillPerSecond;
        private final double capacity;
        private double tokens;
        private Instant lastRefill;

        private Bucket(double refillPerSecond, double capacity, Instant now) {
            this.refillPerSecond = refillPerSecond;
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefill = now;
        }

        private synchronized Decision tryAcquire(Instant now) {
            long nanos = Duration.between(lastRefill, now).toNanos();
            if (nanos > 0) {
                tokens = Math.min(capacity, tokens + nanos / 1_000_000_000.0 * refillPerSecond);
                lastRefill = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return Decision.allowAll();
            }
            long waitNanos = (long) Math.ceil((1.0 - tokens) / refillPerSecond * 1_000_000_000.0);
            return new Decision(false, Duration.ofNanos(Math.max(1, waitNanos)));
        }

        private synchronized boolean stale(Instant now) {
            return !now.isBefore(lastRefill.plusSeconds(600));
        }
    }
}
