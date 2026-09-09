package com.gatehousemc.whitelistrequest.platform.fabric;

import com.gatehousemc.whitelistrequest.domain.PlayerIdentity;
import com.gatehousemc.whitelistrequest.port.VanillaWhitelistPort;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WhitelistEntry;

import java.util.concurrent.CompletableFuture;

public final class FabricVanillaWhitelistAdapter implements VanillaWhitelistPort {
    private final MinecraftServer server;

    public FabricVanillaWhitelistAdapter(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
        return onServerThread(() -> server.getPlayerManager().getWhitelist().isAllowed(profile(identity)));
    }

    @Override
    public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
        return onServerThread(() -> {
            server.getPlayerManager().getWhitelist().add(new WhitelistEntry(profile(identity)));
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
        return onServerThread(() -> {
            server.getPlayerManager().getWhitelist().remove(profile(identity));
            return null;
        });
    }

    private GameProfile profile(PlayerIdentity identity) {
        return new GameProfile(identity.offlineUuid(), identity.exactUsername());
    }

    private <T> CompletableFuture<T> onServerThread(java.util.function.Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        return result;
    }
}
