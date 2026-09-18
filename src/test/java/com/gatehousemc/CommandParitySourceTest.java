package com.gatehousemc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandParitySourceTest {
    private static final List<String> COMMAND_FILES = List.of(
            "src/main/java/com/gatehousemc/platform/fabric/command/GatehouseCommands.java",
            "platform-forge/src/main/java/com/gatehousemc/platform/forge/GatehouseForgeCommands.java",
            "platform-neoforge/src/main/java/com/gatehousemc/platform/neoforge/GatehouseNeoForgeCommands.java");

    private static final List<String> SUBCOMMANDS = List.of(
            "list", "show", "approve", "deny", "block", "undo", "unblock", "status", "reload");

    @Test
    void everyLoaderExposesTheSameAdminRecoverySurface() throws IOException {
        for (String file : COMMAND_FILES) {
            String source = Files.readString(Path.of(file));
            for (String subcommand : SUBCOMMANDS) {
                assertTrue(source.contains("literal(\"" + subcommand + "\")")
                                || source.contains("decision(\"" + subcommand + "\""),
                        () -> file + " is missing /gatehouse " + subcommand);
            }
            assertTrue(source.contains("\"resolving\""),
                    () -> file + " does not expose the RESOLVING status filter");
            assertTrue(source.contains("Messages.get(\"command.help\")"),
                    () -> file + " does not provide root command help");
            assertFalse(source.contains("substring(0, 8)"),
                    () -> file + " displays a request ID that cannot be resolved by commands");
        }
    }
}
