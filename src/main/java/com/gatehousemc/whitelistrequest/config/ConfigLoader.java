package com.gatehousemc.whitelistrequest.config;

import com.gatehousemc.whitelistrequest.port.RoutingMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ConfigLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);

    private ConfigLoader() {}

    public static ModConfig loadOrDefault(Path configDir) throws IOException {
        Path path = configDir.resolve("config.json");
        if (!Files.exists(path)) {
            Files.createDirectories(configDir);
            Files.writeString(path, defaultJson(configDir), StandardCharsets.UTF_8);
            return ModConfig.defaults(configDir);
        }
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        expandProviderToken(root, "discord", System.getenv());
        expandProviderToken(root, "telegram", System.getenv());
        return parse(root, configDir);
    }

    static ModConfig parse(JsonObject root, Path configDir) {
        ModConfig defaults = ModConfig.defaults(configDir);
        JsonObject requests = object(root, "requests");
        JsonObject database = object(root, "database");
        JsonObject routing = object(root, "routing");
        JsonObject discord = object(root, "discord");
        JsonObject telegram = object(root, "telegram");

        long cooldown = longValue(requests, "denialCooldownMinutes", defaults.requests().denialCooldownMinutes());
        int queue = intValue(requests, "queueCapacity", defaults.requests().queueCapacity());
        int permission = intValue(requests, "commandPermissionLevel", defaults.requests().commandPermissionLevel());
        if (cooldown < 0 || queue < 1 || permission < 0 || permission > 4) {
            throw new IllegalArgumentException("Invalid requests configuration");
        }

        Path dbPath = Path.of(stringValue(database, "path", defaults.database().path().toString()));
        int busyTimeout = intValue(database, "busyTimeoutMs", defaults.database().busyTimeoutMs());
        if (busyTimeout < 0) throw new IllegalArgumentException("database.busyTimeoutMs must be non-negative");

        RoutingMode mode;
        try {
            mode = RoutingMode.valueOf(stringValue(routing, "mode", defaults.routing().mode().name()).toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("routing.mode must be PRIMARY_FALLBACK, FANOUT, or FIRST_SUCCESS", exception);
        }
        List<String> providers = strings(routing, "providers", defaults.routing().providers());
        for (String provider : providers) {
            if (!provider.equalsIgnoreCase("discord") && !provider.equalsIgnoreCase("telegram")) {
                throw new IllegalArgumentException("routing.providers contains unsupported provider: " + provider);
            }
        }

        ModConfig.Discord discordConfig = new ModConfig.Discord(
                boolValue(discord, "enabled", defaults.discord().enabled()),
                stringValue(discord, "token", defaults.discord().token()),
                stringValue(discord, "guildId", defaults.discord().guildId()),
                stringValue(discord, "channelId", defaults.discord().channelId()),
                strings(discord, "allowedUserIds", defaults.discord().allowedUserIds()),
                strings(discord, "allowedRoleIds", defaults.discord().allowedRoleIds()));

        ModConfig.Telegram telegramConfig = new ModConfig.Telegram(
                boolValue(telegram, "enabled", defaults.telegram().enabled()),
                stringValue(telegram, "token", defaults.telegram().token()),
                stringValue(telegram, "chatId", defaults.telegram().chatId()),
                strings(telegram, "allowedUserIds", defaults.telegram().allowedUserIds()));

        return new ModConfig(new ModConfig.Requests(cooldown, queue, permission),
                new ModConfig.Database(dbPath, busyTimeout),
                new ModConfig.Routing(mode, providers), discordConfig, telegramConfig);
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static void expandProviderToken(JsonObject root, String provider, Map<String, String> environment) {
        JsonObject config = object(root, provider);
        boolean enabled = boolValue(config, "enabled", false);
        String token = stringValue(config, "token", "");
        if (!token.contains("${") || !enabled) return;
        try {
            config.addProperty("token", EnvExpander.expand(token, environment));
        } catch (IllegalArgumentException error) {
            // A provider secret must not take the Minecraft request path down.
            // Disable only the affected adapter; the durable core workflow
            // remains available through commands and other providers.
            config.addProperty("enabled", false);
            config.addProperty("token", "");
            LOGGER.warn("{} provider disabled: {}", provider, error.getMessage());
        }
    }

    private static String stringValue(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static boolean boolValue(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static List<String> strings(JsonObject object, String key, List<String> fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) result.add(element.getAsString());
        return result;
    }

    private static String defaultJson(Path configDir) {
        return "{\n" +
                "  \"requests\": { \"denialCooldownMinutes\": 1440, \"queueCapacity\": 10000, \"commandPermissionLevel\": 3 },\n" +
                "  \"database\": { \"path\": \"" + configDir.resolve("requests.sqlite").toString().replace("\\", "\\\\") + "\", \"busyTimeoutMs\": 5000 },\n" +
                "  \"routing\": { \"mode\": \"PRIMARY_FALLBACK\", \"providers\": [\"discord\", \"telegram\"] },\n" +
                "  \"discord\": { \"enabled\": false, \"token\": \"\", \"guildId\": \"\", \"channelId\": \"\", \"allowedUserIds\": [], \"allowedRoleIds\": [] },\n" +
                "  \"telegram\": { \"enabled\": false, \"token\": \"\", \"chatId\": \"\", \"allowedUserIds\": [] }\n" +
                "}\n";
    }
}
