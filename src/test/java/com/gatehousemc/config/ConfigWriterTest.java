package com.gatehousemc.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigWriterTest {
    @Test
    void providerRewritePreservesRawTokenAndRemovesLegacyKeys(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("config.json");
        Files.writeString(file, """
                {"discord":{"token":"${DISCORD_TOKEN}","allowedUserIds":["123456789012345678"],"allowedRoleIds":[]},"unrelated":{"keep":true}}
                """);
        new ConfigWriter().writeDiscordPrincipals(file, List.of(
                new ModConfig.Principal("USER", "123456789012345678", "MANAGE")));
        String value = Files.readString(file);
        assertTrue(value.contains("${DISCORD_TOKEN}"));
        assertTrue(value.contains("MANAGE"));
        assertFalse(value.contains("allowedUserIds"));
        assertTrue(value.contains("unrelated"));
    }
}
