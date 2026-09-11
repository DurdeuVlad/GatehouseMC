package com.gatehousemc.platform.fabric;

import com.gatehousemc.application.DecisionService;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.domain.PlayerIdentity;
import com.gatehousemc.domain.WhitelistRequest;
import com.gatehousemc.port.WorkflowRepository;
import com.gatehousemc.runtime.GatehouseRuntime;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Fabric lifecycle facade over the shared application runtime. */
public final class FabricRuntime implements AutoCloseable {
    private final MinecraftServer server;
    private final GatehouseRuntime delegate;

    private FabricRuntime(MinecraftServer server, GatehouseRuntime delegate) {
        this.server = server;
        this.delegate = delegate;
    }

    static FabricRuntime start(MinecraftServer server, ModConfig config) throws IOException, SQLException {
        return new FabricRuntime(server, GatehouseRuntime.start(config, new FabricVanillaWhitelistAdapter(server)));
    }

    static FabricRuntime degraded(MinecraftServer server, ModConfig config) {
        return new FabricRuntime(server, GatehouseRuntime.degraded(config));
    }

    public Text onWhitelistDenied(GameProfileIdentity profile) {
        GatehouseRuntime.AdmissionResponse response = delegate.onWhitelistDenied(profile.toDomain());
        return Text.literal(response.message());
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
