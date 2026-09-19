package com.gatehousemc.application;

import java.util.concurrent.atomic.AtomicInteger;

/** In-memory count of PENDING + RESOLVING workflow rows used by admission control. */
public final class ActiveRequestCounter {
    private final AtomicInteger count;

    public ActiveRequestCounter(int initialCount) {
        if (initialCount < 0) throw new IllegalArgumentException("initialCount must be non-negative");
        this.count = new AtomicInteger(initialCount);
    }

    public int current() {
        return count.get();
    }

    public boolean tryReserve(int maximum) {
        if (maximum < 1) return false;
        while (true) {
            int current = count.get();
            if (current >= maximum) return false;
            if (count.compareAndSet(current, current + 1)) return true;
        }
    }

    public void increment() {
        count.incrementAndGet();
    }

    public void decrement() {
        count.updateAndGet(value -> Math.max(0, value - 1));
    }
}
