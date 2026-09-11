package com.gatehousemc.platform.neoforge;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.port.WorkflowRepository;
import com.gatehousemc.runtime.GatehouseRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class NeoForgeRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final GatehouseRuntime delegate;

    private NeoForgeRuntime(MinecraftServer server, GatehouseRuntime delegate) {
        this.server = server;
        this.delegate = delegate;
    }

    static NeoForgeRuntime start(MinecraftServer server, ModConfig config) throws IOException, SQLException {
        return new NeoForgeRuntime(server, GatehouseRuntime.start(config, new NeoForgeVanillaWhitelistAdapter(server)));
    }

    static NeoForgeRuntime degraded(MinecraftServer server, ModConfig config) {
        return new NeoForgeRuntime(server, GatehouseRuntime.degraded(config));
    }

    public Component onWhitelistDenied(GameProfileIdentity profile) {
        return Component.literal(delegate.onWhitelistDenied(profile.toDomain()).message());
    }

    public DecisionService decisions() { return delegate.decisions(); }
    public WorkflowRepository repository() { return delegate.repository(); }
    public ModConfig config() { return delegate.config(); }
    public ExecutorService commandExecutor() { return delegate.commandExecutor(); }
    public int queueSize() { return delegate.queueSize(); }
    public boolean degraded() { return delegate.degraded(); }
    public Optional<WhitelistRequest> find(UUID id) { return delegate.find(id); }
    public Optional<WhitelistRequest> active(String username) { return delegate.active(username); }
    MinecraftServer server() { return server; }

    @Override
    public void close() { delegate.close(); }

    public record GameProfileIdentity(UUID uuid, String exactUsername) {
        public PlayerIdentity toDomain() { return PlayerIdentity.of(uuid, exactUsername); }
    }
}
