package com.gatehousemc.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable set of translations for a single language.
 * Falls back to the key itself if no translation is found.
 */
public final class TranslationBundle {
    private static final String RESOURCE_PREFIX = "/assets/gatehousemc/lang/";
    private static final String RESOURCE_SUFFIX = ".json";

    private final String language;
    private final Map<String, String> entries;

    private TranslationBundle(String language, Map<String, String> entries) {
        this.language = language;
        this.entries = Map.copyOf(entries);
    }

    String language() { return language; }

    String get(String key) {
        return entries.getOrDefault(key, key);
    }

    static TranslationBundle load(String language) {
        Objects.requireNonNull(language, "language");
        String normalized = language.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) normalized = "en_us";
        try (InputStream stream = TranslationBundle.class.getResourceAsStream(RESOURCE_PREFIX + normalized + RESOURCE_SUFFIX)) {
            if (stream == null) {
                if (normalized.equals("en_us")) return new TranslationBundle(normalized, Map.of());
                return load("en_us");
            }
            String json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> entries = parseJson(json);
            return new TranslationBundle(normalized, entries);
        } catch (IOException error) {
            throw new IllegalStateException("Failed to load language file: " + normalized, error);
        }
    }

    private static Map<String, String> parseJson(String json) {
        Map<String, String> result = new HashMap<>();
        int i = 0;
        int len = json.length();
        while (i < len) {
            int keyStart = json.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = json.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = unescape(json.substring(keyStart + 1, keyEnd));
            int colon = json.indexOf(':', keyEnd);
            if (colon < 0) break;
            int valueStart = json.indexOf('"', colon + 1);
            if (valueStart < 0) break;
            int valueEnd = findClosingQuote(json, valueStart + 1);
            if (valueEnd < 0) break;
            String value = unescape(json.substring(valueStart + 1, valueEnd));
            result.put(key, value);
            i = valueEnd + 1;
        }
        return result;
    }

    private static int findClosingQuote(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private static String unescape(String value) {
        return value.replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .replace("\\t", "\t");
    }
}
