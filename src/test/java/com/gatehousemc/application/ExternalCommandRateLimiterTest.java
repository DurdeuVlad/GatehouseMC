package com.gatehousemc.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ExternalCommandRateLimiterTest {
    @Test
    void readAndMutationBucketsHaveSeparateFixedBurstLimits() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        ExternalCommandRateLimiter limiter = new ExternalCommandRateLimiter(now::get);

        for (int i = 0; i < 10; i++) assertTrue(limiter.allow("discord", "actor", false).allowed());
        assertFalse(limiter.allow("discord", "actor", false).allowed());
        for (int i = 0; i < 4; i++) assertTrue(limiter.allow("discord", "actor", true).allowed());
        assertFalse(limiter.allow("discord", "actor", true).allowed());
    }

    @Test
    void refillReturnsRetryAfterAndIsScopedToStableActor() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);
        ExternalCommandRateLimiter limiter = new ExternalCommandRateLimiter(now::get);
        for (int i = 0; i < 4; i++) limiter.allow("telegram", "one", true);
        ExternalCommandRateLimiter.Decision rejected = limiter.allow("telegram", "one", true);
        assertFalse(rejected.allowed());
        assertTrue(rejected.retryAfter().toMillis() > 0);
        assertTrue(limiter.allow("telegram", "two", true).allowed());
        now.set(Instant.EPOCH.plusSeconds(1));
        assertTrue(limiter.allow("telegram", "one", true).allowed());
    }

    @Test
    void unboundedActorIdsCannotGrowTheLimiterMapForever() {
        ExternalCommandRateLimiter limiter = new ExternalCommandRateLimiter(Instant::now);
        for (int i = 0; i < ExternalCommandRateLimiter.MAX_BUCKETS + 100; i++) {
            limiter.allow("telegram", "actor-" + i, true);
        }

        assertTrue(limiter.bucketCount() <= ExternalCommandRateLimiter.MAX_BUCKETS);
        assertFalse(limiter.allow("telegram", "actor-overflow", true).allowed());
    }
}
