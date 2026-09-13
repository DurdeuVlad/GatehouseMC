package com.gatehousemc.platform.neoforge;

import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.port.VanillaWhitelistPort;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserWhiteListEntry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class NeoForgeVanillaWhitelistAdapter implements VanillaWhitelistPort {
    private final MinecraftServer server;

    public NeoForgeVanillaWhitelistAdapter(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public CompletableFuture<Boolean> isWhitelisted(PlayerIdentity identity) {
        return onServerThread(() -> {
            boolean allowed = server.getPlayerList().getWhiteList().isWhiteListed(profile(identity));
            if (!allowed && server.usesAuthentication()) {
                allowed = server.getPlayerList().getWhiteList().isWhiteListed(new GameProfile(identity.offlineUuid(), identity.exactUsername()));
            }
            return allowed;
        });
    }

    @Override
    public CompletableFuture<Void> addExactProfile(PlayerIdentity identity) {
        return onServerThread(() -> {
            server.getPlayerList().getWhiteList().add(new UserWhiteListEntry(profile(identity)));
            saveWhitelist();
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> removeExactProfile(PlayerIdentity identity) {
        return onServerThread(() -> {
            server.getPlayerList().getWhiteList().remove(profile(identity));
            if (server.usesAuthentication()) {
                server.getPlayerList().getWhiteList().remove(new GameProfile(identity.offlineUuid(), identity.exactUsername()));
            }
            saveWhitelist();
            return null;
        });
    }

    private GameProfile profile(PlayerIdentity identity) {
        if (server.usesAuthentication() && server.getProfileCache() != null) {
            java.util.Optional<GameProfile> cached = server.getProfileCache().get(identity.exactUsername());
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        return new GameProfile(identity.offlineUuid(), identity.exactUsername());
    }

    private void saveWhitelist() {
        try {
            server.getPlayerList().getWhiteList().save();
        } catch (IOException error) {
            throw new UncheckedIOException("Could not persist the vanilla whitelist", error);
        }
    }

    private <T> CompletableFuture<T> onServerThread(Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        server.execute(() -> {
            try { result.complete(action.get()); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }
}
