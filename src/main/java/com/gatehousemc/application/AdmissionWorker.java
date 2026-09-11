package com.gatehousemc.application;

import com.gatehousemc.domain.PlayerIdentity;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Loader-neutral admission worker. */
public final class AdmissionWorker implements AutoCloseable {
    private final ArrayBlockingQueue<PlayerIdentity> queue;
    private final WhitelistRequestService service;
    private final ExecutorService executor;
    private volatile boolean running;

    public AdmissionWorker(int capacity, WhitelistRequestService service) {
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.service = service;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "gatehousemc-persistence");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        running = true;
        executor.submit(() -> {
            while (running || !queue.isEmpty()) {
                try {
                    PlayerIdentity identity = queue.poll(250, TimeUnit.MILLISECONDS);
                    if (identity != null) service.recordAttempt(identity);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (RuntimeException ignored) {
                    service.cache().markDegraded(true);
                }
            }
        });
    }

    public boolean offer(PlayerIdentity identity) {
        if (!running || service.cache().isDegraded()) return false;
        boolean accepted = queue.offer(identity);
        if (!accepted) service.cache().put(identity.normalizedUsername(), AdmissionState.degraded());
        return accepted;
    }

    public int size() { return queue.size(); }

    @Override
    public void close() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
