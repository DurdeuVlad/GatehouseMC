package com.gatehousemc.port;

import com.gatehousemc.domain.PlayerIdentity;

import java.util.concurrent.CompletableFuture;

public interface VanillaWhitelistPort {
    CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity);

    CompletableFuture<Void> addExactProfile(PlayerIdentity identity);

    CompletableFuture<Void> removeExactProfile(PlayerIdentity identity);
}
