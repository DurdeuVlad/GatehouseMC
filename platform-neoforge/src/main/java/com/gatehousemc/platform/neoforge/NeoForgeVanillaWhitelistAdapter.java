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
        return onServerThread(() -> server.getPlayerList().getWhiteList().isWhiteListed(profile(identity)));
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
            saveWhitelist();
            return null;
        });
    }

    private GameProfile profile(PlayerIdentity identity) {
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
