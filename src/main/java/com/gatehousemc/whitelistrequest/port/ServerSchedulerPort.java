package com.gatehousemc.whitelistrequest.port;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface ServerSchedulerPort {
    <T> CompletableFuture<T> submit(Supplier<T> task);
}
