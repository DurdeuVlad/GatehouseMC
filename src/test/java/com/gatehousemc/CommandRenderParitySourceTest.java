package com.gatehousemc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the canonical operator-facing rendering contract. Every surface that
 * answers an {@code AdminCommandResult} — the three Minecraft loaders, Discord,
 * and Telegram — must delegate to {@code AdminResultRenderer} so the request
 * list payload cannot drift, be reduced to a bare count, or be dropped entirely.
 *
 * This test exists because a previous version of this guard was removed during
 * the multiloader refactor, which allowed the list output to silently regress on
 * every surface except Fabric.
 */
class CommandRenderParitySourceTest {
    private static final List<String> COMMAND_SURFACES = List.of(
            "src/main/java/com/gatehousemc/platform/fabric/command/GatehouseCommands.java",
            "platform-forge/src/main/java/com/gatehousemc/platform/forge/GatehouseForgeCommands.java",
            "platform-neoforge/src/main/java/com/gatehousemc/platform/neoforge/GatehouseNeoForgeCommands.java",
            "src/main/java/com/gatehousemc/integration/discord/DiscordApprovalInterface.java",
            "src/main/java/com/gatehousemc/integration/telegram/TelegramApprovalInterface.java");

    @Test
    void everySurfaceDelegatesToTheCanonicalResultRenderer() throws IOException {
        for (String file : COMMAND_SURFACES) {
            String source = Files.readString(Path.of(file));
            assertTrue(source.contains("AdminResultRenderer.render("),
                    () -> file + " answers admin commands without the canonical AdminResultRenderer");
        }
    }

    @Test
    void noSurfaceCollapsesListsToACountOrReimplementsRowRendering() throws IOException {
        for (String file : COMMAND_SURFACES) {
            String source = Files.readString(Path.of(file));
            assertFalse(source.contains("results)\""),
                    () -> file + " still reduces request lists to a bare result count");
            assertFalse(source.contains("instanceof java.util.List")
                            || source.contains("instanceof List<?>"),
                    () -> file + " reimplements list rendering instead of delegating to AdminResultRenderer");
            assertFalse(source.contains("(WhitelistRequest)"),
                    () -> file + " blindly casts list elements to WhitelistRequest (breaks admin list and loses feedback)");
        }
    }
}
