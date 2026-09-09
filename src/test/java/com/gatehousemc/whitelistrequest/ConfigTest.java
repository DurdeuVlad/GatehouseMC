package com.gatehousemc.whitelistrequest.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {
    @Test
    void expandsSecretsWithoutWritingThemBack() {
        assertEquals("prefix-secret", EnvExpander.expand("prefix-${TOKEN}", Map.of("TOKEN", "secret")));
    }

    @Test
    void missingSecretIsActionable() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EnvExpander.expand("${MISSING_TOKEN}", Map.of()));
        assertTrue(error.getMessage().contains("MISSING_TOKEN"));
    }

    @Test
    void invalidEnabledDiscordProviderIsDisabledWithoutBreakingCore(@TempDir Path configDir) {
        ModConfig config = ConfigLoader.parse(JsonParser.parseString("""
                {
                  "discord": {
                    "enabled": true,
                    "token": "token",
                    "guildId": "123456789012345678",
                    "channelId": "123456789012345678",
                    "allowedUserIds": [],
                    "allowedRoleIds": []
                  }
                }
                """).getAsJsonObject(), configDir);

        assertFalse(config.discord().enabled());
        assertFalse(config.telegram().enabled());
    }

    @Test
    void invalidEnabledTelegramProviderIsDisabledWithoutBreakingCore(@TempDir Path configDir) {
        ModConfig config = ConfigLoader.parse(JsonParser.parseString("""
                {
                  "telegram": {
                    "enabled": true,
                    "token": "",
                    "chatId": "not-a-chat-id",
                    "allowedUserIds": []
                  }
                }
                """).getAsJsonObject(), configDir);

        assertFalse(config.telegram().enabled());
        assertFalse(config.discord().enabled());
    }

    @Test
    void duplicateRoutingProvidersAreRejected(@TempDir Path configDir) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.parse(JsonParser.parseString("""
                        { "routing": { "providers": ["discord", "discord"] } }
                        """).getAsJsonObject(), configDir));

        assertTrue(error.getMessage().contains("duplicate"));
    }

    @Test
    void blankDatabasePathIsRejected(@TempDir Path configDir) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.parse(JsonParser.parseString("""
                        { "database": { "path": "" } }
                        """).getAsJsonObject(), configDir));

        assertTrue(error.getMessage().contains("database.path"));
    }

    @Test
    void invalidDiscordAllowlistIsDisabled(@TempDir Path configDir) {
        ModConfig config = ConfigLoader.parse(JsonParser.parseString("""
                {
                  "discord": {
                    "enabled": true,
                    "token": "token",
                    "guildId": "123456789012345678",
                    "channelId": "123456789012345678",
                    "allowedUserIds": ["not-a-snowflake"],
                    "allowedRoleIds": []
                  }
                }
                """).getAsJsonObject(), configDir);

        assertFalse(config.discord().enabled());
    }

    @Test
    void malformedProviderFieldIsDisabled(@TempDir Path configDir) {
        ModConfig config = ConfigLoader.parse(JsonParser.parseString("""
                { "discord": { "enabled": {} } }
                """).getAsJsonObject(), configDir);

        assertFalse(config.discord().enabled());
    }

    @Test
    void providerTokensAreRedactedFromRecordStrings(@TempDir Path configDir) {
        ModConfig defaults = ModConfig.defaults(configDir);
        ModConfig config = new ModConfig(defaults.requests(), defaults.database(), defaults.routing(),
                new ModConfig.Discord(true, "discord-secret", "123456789012345678", "123456789012345678",
                        List.of("123456789012345678"), List.of()),
                new ModConfig.Telegram(true, "telegram-secret", "-1001234567890", List.of("123456789")));

        assertFalse(config.toString().contains("discord-secret"));
        assertFalse(config.toString().contains("telegram-secret"));
        assertFalse(config.discord().toString().contains("discord-secret"));
        assertFalse(config.telegram().toString().contains("telegram-secret"));
    }

    @Test
    void malformedEnvironmentPlaceholderIsRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EnvExpander.expand("${ DISCORD_TOKEN}", Map.of()));

        assertTrue(error.getMessage().contains("placeholder"));
    }

    @Test
    void databaseDirectoryIsRejected(@TempDir Path configDir) throws IOException {
        Path databaseDirectory = Files.createDirectory(configDir.resolve("database"));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.parse(JsonParser.parseString("""
                        { "database": { "path": "%s" } }
                        """.formatted(databaseDirectory.toString().replace("\\", "\\\\"))).getAsJsonObject(), configDir));

        assertTrue(error.getMessage().contains("database.path"));
    }

    @Test
    void nonObjectConfigRootIsRejected(@TempDir Path configDir) throws IOException {
        Files.writeString(configDir.resolve("config.json"), "[]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ConfigLoader.loadOrDefault(configDir));

        assertTrue(error.getMessage().contains("JSON object"));
    }
}
