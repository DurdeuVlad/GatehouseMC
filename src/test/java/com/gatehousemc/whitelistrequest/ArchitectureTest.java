package com.gatehousemc.whitelistrequest;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ArchitectureTest {
    private static final List<String> CORE_PACKAGES = List.of("domain", "application", "port");
    private static final List<String> FORBIDDEN_IMPORTS = List.of(
            "net.minecraft.",
            "net.fabricmc.",
            "net.dv8tion.jda.",
            "java.sql.",
            "org.sqlite."
    );

    @Test
    void pureCoreDoesNotImportPlatformOrIntegrationTypes() throws IOException {
        Path sourceRoot = Path.of("src/main/java/com/gatehousemc/whitelistrequest");
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> CORE_PACKAGES.stream().anyMatch(pkg -> path.toString().replace('\\', '/').contains("/" + pkg + "/")))
                    .filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String source = Files.readString(path);
                            FORBIDDEN_IMPORTS.forEach(forbidden -> assertFalse(
                                    source.contains("import " + forbidden),
                                    () -> path + " imports forbidden core dependency " + forbidden));
                        } catch (IOException error) {
                            throw new IllegalStateException("Could not inspect " + path, error);
                        }
                    });
        }
    }
}
