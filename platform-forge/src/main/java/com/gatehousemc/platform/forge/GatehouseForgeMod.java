package com.gatehousemc.platform.forge;

import com.gatehousemc.config.ConfigLoader;
import com.gatehousemc.config.ModConfig;
import com.gatehousemc.i18n.Messages;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Mod(GatehouseForgeMod.MOD_ID)
public final class GatehouseForgeMod {
    public static final String MOD_ID = "gatehousemc";
    private static final ExecutorService STARTUP_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gatehousemc-forge-startup");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean STOPPING = new AtomicBoolean();
    private static volatile ForgeRuntime runtime;

    public GatehouseForgeMod() {
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
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
            ForgeRuntime started = ForgeRuntime.start(server, config);
            if (STOPPING.get() || runtime != null) {
                started.close();
                return;
            }
            runtime = started;
        } catch (Exception error) {
            if (!STOPPING.get() && runtime == null) {
                runtime = ForgeRuntime.degraded(server, config == null ? ModConfig.defaults(configDir) : config);
                System.err.println("GatehouseMC failed to start: " + error.getMessage());
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        STOPPING.set(true);
        ForgeRuntime current = runtime;
        runtime = null;
        if (current != null) current.close();
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        GatehouseForgeCommands.register(event.getDispatcher(), () -> runtime);
    }

    public static Component handleWhitelistDenial(GameProfile profile) {
        ForgeRuntime current = runtime;
        if (current == null) return Component.translatable("multiplayer.disconnect.not_whitelisted");
        return current.onWhitelistDenied(new ForgeRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
    }

    public static ForgeRuntime runtime() { return runtime; }
}
