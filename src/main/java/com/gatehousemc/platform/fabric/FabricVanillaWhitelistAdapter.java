package com.gatehousemc.platform.fabric;

import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.port.VanillaWhitelistPort;
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
        return onServerThread(() -> {
            boolean allowed = server.getPlayerManager().getWhitelist().isAllowed(profile(identity));
            if (!allowed && server.isOnlineMode()) {
                allowed = server.getPlayerManager().getWhitelist().isAllowed(new GameProfile(identity.offlineUuid(), identity.exactUsername()));
            }
            return allowed;
        });
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
            if (server.isOnlineMode()) {
                server.getPlayerManager().getWhitelist().remove(new GameProfile(identity.offlineUuid(), identity.exactUsername()));
            }
            return null;
        });
    }

    private GameProfile profile(PlayerIdentity identity) {
        if (server.isOnlineMode() && server.getUserCache() != null) {
            GameProfile cached = server.getUserCache().findByName(identity.exactUsername());
            if (cached != null) {
                return cached;
            }
        }
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
