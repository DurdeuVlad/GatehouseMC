package com.gatehousemc.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Atomic non-secret config writer. It never expands or replaces token fields. */
public final class ConfigWriter {
    private static final com.google.gson.Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    public void update(Path configFile, Consumer<JsonObject> mutation) throws IOException {
        Objects.requireNonNull(configFile, "configFile");
        Objects.requireNonNull(mutation, "mutation");
        JsonObject root = JsonParser.parseString(Files.readString(configFile, StandardCharsets.UTF_8)).getAsJsonObject();
        mutation.accept(root);
        Path parent = configFile.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("config file has no parent directory");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, configFile.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, JSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void writeDiscordPrincipals(Path configFile, List<ModConfig.Principal> principals) throws IOException {
        update(configFile, root -> {
            JsonObject discord = object(root, "discord");
            discord.add("principals", principalArray(principals, false));
            discord.remove("allowedUserIds");
            discord.remove("allowedRoleIds");
            root.add("discord", discord);
        });
    }

    public void writeTelegramPrincipals(Path configFile, List<ModConfig.Principal> principals) throws IOException {
        update(configFile, root -> {
            JsonObject telegram = object(root, "telegram");
            telegram.add("principals", principalArray(principals, true));
            telegram.remove("allowedUserIds");
            root.add("telegram", telegram);
        });
    }

    private static JsonObject object(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonObject()) return root.getAsJsonObject(key);
        JsonObject value = new JsonObject();
        root.add(key, value);
        return value;
    }

    private static JsonArray principalArray(List<ModConfig.Principal> principals, boolean telegram) {
        JsonArray result = new JsonArray();
        for (ModConfig.Principal principal : principals) {
            JsonObject value = new JsonObject();
            if (!telegram) value.addProperty("kind", principal.kind());
            value.addProperty(telegram ? "userId" : "id", principal.id());
            value.addProperty("access", principal.access());
            result.add(value);
        }
        return result;
    }
}
