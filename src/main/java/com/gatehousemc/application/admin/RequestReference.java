package com.gatehousemc.application.admin;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Parsed request reference. UUID prefixes are deliberately unambiguous-only at resolution time. */
public record RequestReference(Kind kind, String value) {
    private static final Pattern UUID_PREFIX = Pattern.compile("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{1,4}){0,4}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    public RequestReference {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("request reference must not be blank");
        value = value.trim();
    }

    public enum Kind { UUID, UUID_PREFIX, USERNAME }

    public static RequestReference parse(String raw) {
        if (raw == null) throw new IllegalArgumentException("request reference is required");
        String value = raw.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("request reference must not be blank");
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("UUID must use canonical form");
            }
            return new RequestReference(Kind.UUID, uuid.toString());
        } catch (IllegalArgumentException ignored) {
            // Continue with the compact UUID prefix / exact Minecraft username forms.
        }
        if (UUID_PREFIX.matcher(value).matches()) {
            return new RequestReference(Kind.UUID_PREFIX, value.toLowerCase(Locale.ROOT));
        }
        if (USERNAME.matcher(value).matches()) {
            return new RequestReference(Kind.USERNAME, value);
        }
        throw new IllegalArgumentException("request reference must be a canonical UUID, an 8+ hex prefix, or a Minecraft username");
    }

    public String normalizedUsername() {
        if (kind != Kind.USERNAME) throw new IllegalStateException("reference is not a username");
        return value.toLowerCase(Locale.ROOT);
    }
}
