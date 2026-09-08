package com.gatehousemc.whitelistrequest;

import com.gatehousemc.whitelistrequest.config.EnvExpander;
import org.junit.jupiter.api.Test;

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
}
