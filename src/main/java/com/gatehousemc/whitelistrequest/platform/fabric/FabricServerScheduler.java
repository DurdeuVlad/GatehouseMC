package com.gatehousemc.whitelistrequest.platform.fabric;

import com.gatehousemc.whitelistrequest.port.ServerSchedulerPort;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class FabricServerScheduler implements ServerSchedulerPort {
    private final MinecraftServer server;

    public FabricServerScheduler(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(task.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
