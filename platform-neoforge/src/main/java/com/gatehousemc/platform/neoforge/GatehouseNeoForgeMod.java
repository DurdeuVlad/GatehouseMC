package com.gatehousemc.platform.neoforge;

import com.gatehousemc.config.ConfigLoader;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.i18n.Messages;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(GatehouseNeoForgeMod.MOD_ID)
public final class GatehouseNeoForgeMod {
    public static final String MOD_ID = "gatehousemc";
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gatehousemc-neoforge-startup");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static volatile NeoForgeRuntime runtime;

    public GatehouseNeoForgeMod() {
        NeoForge.EVENT_BUS.addListener(this::onServerStarted);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onServerStarted(ServerStartedEvent event) {
        STOPPING.set(false);
        STARTUP_EXECUTOR.execute(() -> startRuntime(event.getServer(), FMLPaths.CONFIGDIR.get().resolve(MOD_ID)));
    }

    private static void startRuntime(net.minecraft.server.MinecraftServer server, Path configDir) {
        if (STOPPING.get()) return;
        ModConfig config = null;
        try {
            config = ConfigLoader.loadOrDefault(configDir);
            Messages.load(config.language());
            NeoForgeRuntime started = NeoForgeRuntime.start(server, config);
            if (STOPPING.get() || runtime != null) {
                started.close();
                return;
            }
            runtime = started;
        } catch (Exception error) {
            if (!STOPPING.get() && runtime == null) {
                runtime = NeoForgeRuntime.degraded(server, config == null ? ModConfig.defaults(configDir) : config);
                System.err.println("GatehouseMC failed to start: " + error.getMessage());
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        STOPPING.set(true);
        NeoForgeRuntime current = runtime;
        runtime = null;
        if (current != null) current.close();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        GatehouseNeoForgeCommands.register(event.getDispatcher(), () -> runtime);
    }

    public static Component handleWhitelistDenial(GameProfile profile) {
        NeoForgeRuntime current = runtime;
        if (current == null) return Component.translatable("multiplayer.disconnect.not_whitelisted");
        return current.onWhitelistDenied(new NeoForgeRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
    }

    public static NeoForgeRuntime runtime() { return runtime; }
}
