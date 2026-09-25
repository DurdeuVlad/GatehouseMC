package com.gatehousemc.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct, deterministic proof for GatehouseRuntime's shutdown-timeout bound (the fix for the
 * production hang where a stuck provider blocked the Minecraft server thread on {@code stop}).
 *
 * <p>A real dedicated-server E2E cannot exercise the "JDA is connected and its shutdown hangs"
 * case without a live, authenticating Discord bot token: JDA 6.5.0's own JDABuilder.build()
 * makes a synchronous REST call to validate the token and self-destructs on failure, so an
 * invalid/fake token never reaches a state where a hang is even possible. This test reproduces
 * the hang directly and deterministically instead, with no network or Minecraft server involved.
 */
class GatehouseRuntimeShutdownBoundTest {

    @Test
    void abandonsATaskThatNeverFinishesInsteadOfBlockingPastTheTimeout() throws InterruptedException {
        CountDownLatch neverReleased = new CountDownLatch(1);
        long start = System.nanoTime();

        boolean finished = GatehouseRuntime.runBounded(
                () -> awaitUninterruptibly(neverReleased),
                Duration.ofMillis(200),
                "test-hanging-shutdown");

        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertFalse(finished, "a task that never completes must be reported as not finished");
        assertTrue(elapsedMillis < 2000,
                "runBounded must return promptly once the timeout elapses, not block on the hung task; took " + elapsedMillis + "ms");
    }

    @Test
    void reportsFinishedWhenTheTaskCompletesWithinTheTimeout() {
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean finished = GatehouseRuntime.runBounded(
                () -> ran.set(true),
                Duration.ofSeconds(5),
                "test-fast-shutdown");

        assertTrue(finished, "a task that completes quickly must be reported as finished");
        assertTrue(ran.get(), "the task must actually have run");
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
