package com.gatehousemc.whitelistrequest.port;

import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;

import java.util.concurrent.CompletableFuture;

public interface VanillaWhitelistPort {
    CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity);

    CompletableFuture<Void> addExactProfile(PlayerIdentity identity);
}
