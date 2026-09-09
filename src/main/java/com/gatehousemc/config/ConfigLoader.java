package com.gatehousemc.config;

import com.gatehousemc.port.RoutingMode;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid JSON in config.json", error);
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("config.json root must be a JSON object");
        JsonObject root = parsed.getAsJsonObject();
        expandProviderToken(root, "discord", System.getenv());
        expandProviderToken(root, "telegram", System.getenv());
        return parse(root, configDir);
    }

    static ModConfig parse(JsonObject root, Path configDir) {
        ModConfig defaults = ModConfig.defaults(configDir);
        JsonObject requests = object(root, "requests");
        JsonObject database = object(root, "database");
        JsonObject routing = object(root, "routing");
        JsonObject discord = providerObject(root, "discord");
        JsonObject telegram = providerObject(root, "telegram");

        long cooldown = longValue(requests, "denialCooldownMinutes", defaults.requests().denialCooldownMinutes());
        int queue = intValue(requests, "queueCapacity", defaults.requests().queueCapacity());
        int permission = intValue(requests, "commandPermissionLevel", defaults.requests().commandPermissionLevel());
        if (cooldown < 0 || queue < 1 || permission < 0 || permission > 4) {
            throw new IllegalArgumentException("Invalid requests configuration");
        }

        String configuredDbPath = stringValue(database, "path", defaults.database().path().toString()).trim();
        if (configuredDbPath.isEmpty()) throw new IllegalArgumentException("database.path must not be blank");
        Path dbPath;
        try {
            dbPath = Path.of(configuredDbPath);
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException("database.path is not a valid path", error);
        }
        if (Files.isDirectory(dbPath)) {
            throw new IllegalArgumentException("database.path must refer to a file, not a directory");
        }
        int busyTimeout = intValue(database, "busyTimeoutMs", defaults.database().busyTimeoutMs());
        if (busyTimeout < 0) throw new IllegalArgumentException("database.busyTimeoutMs must be non-negative");

        RoutingMode mode;
        try {
            mode = RoutingMode.valueOf(stringValue(routing, "mode", defaults.routing().mode().name())
                    .trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("routing.mode must be PRIMARY_FALLBACK or FANOUT", exception);
        }
        List<String> providers = strings(routing, "providers", defaults.routing().providers()).stream()
                .map(provider -> provider.trim().toLowerCase(Locale.ROOT))
                .toList();
        Set<String> seenProviders = new HashSet<>();
        for (String provider : providers) {
            if (!provider.equals("discord") && !provider.equals("telegram")) {
                throw new IllegalArgumentException("routing.providers contains unsupported provider: " + provider);
            }
            if (!seenProviders.add(provider)) {
                throw new IllegalArgumentException("routing.providers contains duplicate provider: " + provider);
            }
        }

        ModConfig.Discord discordConfig = parseDiscord(discord, defaults.discord());
        ModConfig.Telegram telegramConfig = parseTelegram(telegram, defaults.telegram());

        String language = stringValue(root, "language", defaults.language()).trim().toLowerCase(Locale.ROOT);
        if (language.isEmpty()) language = "en_us";

        return new ModConfig(new ModConfig.Requests(cooldown, queue, permission),
                new ModConfig.Database(dbPath, busyTimeout),
                new ModConfig.Routing(mode, providers), discordConfig, telegramConfig, language);
    }

    private static JsonObject providerObject(JsonObject root, String provider) {
        try {
            return object(root, provider);
        } catch (IllegalArgumentException error) {
            LOGGER.warn("{} provider disabled: invalid configuration ({})", provider, error.getMessage());
            return new JsonObject();
        }
    }

    private static ModConfig.Discord parseDiscord(JsonObject discord, ModConfig.Discord defaults) {
        try {
            return validateDiscord(new ModConfig.Discord(
                    boolValue(discord, "enabled", defaults.enabled()),
                    stringValue(discord, "token", defaults.token()),
                    stringValue(discord, "guildId", defaults.guildId()),
                    stringValue(discord, "channelId", defaults.channelId()),
                    strings(discord, "allowedUserIds", defaults.allowedUserIds()).stream().map(String::trim).toList(),
                    strings(discord, "allowedRoleIds", defaults.allowedRoleIds()).stream().map(String::trim).toList()));
        } catch (IllegalArgumentException error) {
            LOGGER.warn("discord provider disabled: invalid configuration ({})", error.getMessage());
            return new ModConfig.Discord(false, "", "", "", List.of(), List.of());
        }
    }

    private static ModConfig.Telegram parseTelegram(JsonObject telegram, ModConfig.Telegram defaults) {
        try {
            return validateTelegram(new ModConfig.Telegram(
                    boolValue(telegram, "enabled", defaults.enabled()),
                    stringValue(telegram, "token", defaults.token()),
                    stringValue(telegram, "chatId", defaults.chatId()).trim(),
                    strings(telegram, "allowedUserIds", defaults.allowedUserIds()).stream().map(String::trim).toList()));
        } catch (IllegalArgumentException error) {
            LOGGER.warn("telegram provider disabled: invalid configuration ({})", error.getMessage());
            return new ModConfig.Telegram(false, "", "", List.of());
        }
    }

    private static ModConfig.Discord validateDiscord(ModConfig.Discord config) {
        if (!config.enabled()) return config;
        List<String> errors = new ArrayList<>();
        if (config.token().isBlank()) errors.add("token is blank");
        if (!isDiscordId(config.guildId())) errors.add("guildId must be a Discord snowflake");
        if (!isDiscordId(config.channelId())) errors.add("channelId must be a Discord snowflake");
        if (config.allowedUserIds().isEmpty() && config.allowedRoleIds().isEmpty()) {
            errors.add("at least one allowed user or role is required");
        }
        if (config.allowedUserIds().stream().anyMatch(userId -> !isDiscordId(userId))) {
            errors.add("allowedUserIds must contain Discord snowflakes");
        }
        if (config.allowedRoleIds().stream().anyMatch(roleId -> !isDiscordId(roleId))) {
            errors.add("allowedRoleIds must contain Discord snowflakes");
        }
        if (!errors.isEmpty()) {
            LOGGER.warn("discord provider disabled: invalid configuration ({})", String.join("; ", errors));
            return new ModConfig.Discord(false, "", "", "", List.of(), List.of());
        }
        return config;
    }

    private static ModConfig.Telegram validateTelegram(ModConfig.Telegram config) {
        if (!config.enabled()) return config;
        List<String> errors = new ArrayList<>();
        if (config.token().isBlank()) errors.add("token is blank");
        if (!config.chatId().matches("-?\\d+")) errors.add("chatId must be numeric");
        if (config.allowedUserIds().isEmpty()) errors.add("at least one allowed user is required");
        if (config.allowedUserIds().stream().anyMatch(userId -> !userId.matches("\\d+"))) {
            errors.add("allowedUserIds must be numeric");
        }
        if (!errors.isEmpty()) {
            LOGGER.warn("telegram provider disabled: invalid configuration ({})", String.join("; ", errors));
            return new ModConfig.Telegram(false, "", "", List.of());
        }
        return config;
    }

    private static boolean isDiscordId(String value) {
        return value != null && value.matches("\\d{17,20}");
    }

    private static JsonObject object(JsonObject root, String key) {
        JsonElement value = root.get(key);
        if (value == null || value.isJsonNull()) return new JsonObject();
        if (!value.isJsonObject()) throw new IllegalArgumentException(key + " must be an object");
        return value.getAsJsonObject();
    }

    private static void expandProviderToken(JsonObject root, String provider, Map<String, String> environment) {
        JsonObject config = providerObject(root, provider);
        try {
            boolean enabled = boolValue(config, "enabled", false);
            String token = stringValue(config, "token", "");
            if (!token.contains("${") || !enabled) return;
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
        if (value == null || value.isJsonNull()) return fallback;
        JsonPrimitive primitive = primitive(value, key);
        if (!primitive.isString()) throw new IllegalArgumentException(key + " must be a string");
        return primitive.getAsString();
    }

    private static boolean boolValue(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        JsonPrimitive primitive = primitive(value, key);
        if (!primitive.isBoolean()) throw new IllegalArgumentException(key + " must be a boolean");
        return primitive.getAsBoolean();
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        JsonPrimitive primitive = primitive(value, key);
        if (!primitive.isNumber()) throw new IllegalArgumentException(key + " must be an integer");
        try {
            return primitive.getAsLong();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    private static int intValue(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        JsonPrimitive primitive = primitive(value, key);
        if (!primitive.isNumber()) throw new IllegalArgumentException(key + " must be an integer");
        try {
            return primitive.getAsInt();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(key + " must be an integer", error);
        }
    }

    private static JsonPrimitive primitive(JsonElement value, String key) {
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(key + " must be a primitive value");
        return value.getAsJsonPrimitive();
    }

    private static List<String> strings(JsonObject object, String key, List<String> fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) return fallback;
        if (!value.isJsonArray()) throw new IllegalArgumentException(key + " must be an array");
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            JsonPrimitive primitive = primitive(element, key);
            if (!primitive.isString()) throw new IllegalArgumentException(key + " must contain strings");
            result.add(primitive.getAsString());
        }
        return result;
    }

    private static String defaultJson(Path configDir) {
        return "{\n" +
                "  \"requests\": { \"denialCooldownMinutes\": 1440, \"queueCapacity\": 10000, \"commandPermissionLevel\": 3 },\n" +
                "  \"database\": { \"path\": \"" + configDir.resolve("requests.sqlite").toString().replace("\\", "\\\\") + "\", \"busyTimeoutMs\": 5000 },\n" +
                "  \"routing\": { \"mode\": \"PRIMARY_FALLBACK\", \"providers\": [\"discord\", \"telegram\"] },\n" +
                "  \"discord\": { \"enabled\": false, \"token\": \"\", \"guildId\": \"\", \"channelId\": \"\", \"allowedUserIds\": [], \"allowedRoleIds\": [] },\n" +
                "  \"telegram\": { \"enabled\": false, \"token\": \"\", \"chatId\": \"\", \"allowedUserIds\": [] },\n" +
                "  \"language\": \"en_us\"\n" +
                "}\n";
    }
}
