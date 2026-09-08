package com.gatehousemc.whitelistrequest.platform.fabric;

import com.gatehousemc.whitelistrequest.config.ConfigLoader;
import com.gatehousemc.whitelistrequest.config.ModConfig;
import com.gatehousemc.whitelistrequest.platform.fabric.command.WhitelistRequestCommands;
import com.mojang.authlib.GameProfile;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Objects;

public final class WhitelistRequestMod implements ModInitializer {
    public static final String MOD_ID = "whitelistrequest";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile FabricRuntime runtime;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                WhitelistRequestCommands.register(dispatcher, () -> runtime));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
            try {
                ModConfig config = ConfigLoader.loadOrDefault(configDir);
                runtime = FabricRuntime.start(server, config);
                LOGGER.info("Whitelist Request started: {}", config.redactedSummary());
            } catch (Exception error) {
                LOGGER.error("storage.degraded: whitelist request persistence is unavailable", error);
                runtime = FabricRuntime.degraded(server, ModConfig.defaults(configDir));
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            FabricRuntime current = runtime;
            runtime = null;
            if (current != null) current.close();
        });
    }

    public static Text handleWhitelistDenial(GameProfile profile) {
        FabricRuntime current = runtime;
        if (current == null) return Text.literal("You are not whitelisted on this server. Please contact a server administrator.");
        return current.onWhitelistDenied(new FabricRuntime.GameProfileIdentity(profile.getId(), profile.getName()));
    }

    public static FabricRuntime runtime() {
        return runtime;
    }

    /**
     * Reloads non-structural configuration without requiring a Minecraft
     * restart. The SQLite path is intentionally structural because moving it
     * while the server is running could split the workflow store.
     */
    public static synchronized String reload() {
        FabricRuntime current = runtime;
        if (current == null) return "Whitelist Request is not running";
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try {
            ModConfig next = ConfigLoader.loadOrDefault(configDir);
            if (!samePath(current.config().database().path(), next.database().path())) {
                return "Config reload rejected: database.path changes require a server restart";
            }
            runtime = null;
            current.close();
            runtime = FabricRuntime.start(current.server(), next);
            LOGGER.info("Whitelist Request configuration reloaded: {}", next.redactedSummary());
            return "Whitelist Request configuration reloaded";
        } catch (Exception error) {
            LOGGER.error("Config reload failed; request workflow is degraded until the server is restarted", error);
            runtime = FabricRuntime.degraded(current.server(), current.config());
            return "Config reload failed; workflow is degraded: " + safeMessage(error);
        }
    }

    private static boolean samePath(Path left, Path right) {
        return Objects.equals(left.toAbsolutePath().normalize(), right.toAbsolutePath().normalize());
    }

    private static String safeMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) cause = cause.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }
}
