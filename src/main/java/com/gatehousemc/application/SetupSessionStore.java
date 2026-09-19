package com.gatehousemc.application;

import com.gatehousemc.port.ClockPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-time, provider-scoped BYOB setup sessions. Plaintext codes never enter storage. */
public final class SetupSessionStore {
    public static final Duration TTL = Duration.ofMinutes(10);
    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    public record Session(String provider, String codeHash, Instant createdAt, Instant expiresAt, boolean consumed) {}
    public record Issued(String provider, String code, Instant expiresAt) {}
    public enum ConsumeStatus { CONSUMED, MISSING, EXPIRED, WRONG_PROVIDER, REPLAY }
    public record ConsumeResult(ConsumeStatus status, Optional<Session> session) {}

    public interface Persistence {
        void save(Session session);
        Optional<Session> find(String codeHash);
        boolean consume(String codeHash);
        void cancelProvider(String provider, Instant now);
    }

    public static final class InMemoryPersistence implements Persistence {
        private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
        @Override public void save(Session session) { sessions.put(session.codeHash(), session); }
        @Override public Optional<Session> find(String codeHash) { return Optional.ofNullable(sessions.get(codeHash)); }
        @Override public boolean consume(String codeHash) {
            AtomicBoolean changed = new AtomicBoolean();
            sessions.computeIfPresent(codeHash, (ignored, session) -> {
                if (session.consumed()) return session;
                changed.set(true);
                return new Session(session.provider(), session.codeHash(), session.createdAt(), session.expiresAt(), true);
            });
            return changed.get();
        }
        @Override public void cancelProvider(String provider, Instant now) { sessions.replaceAll((hash, session) ->
                session.provider().equals(provider) && session.expiresAt().isAfter(now)
                        ? new Session(session.provider(), session.codeHash(), session.createdAt(), session.expiresAt(), true) : session); }
    }

    private final ClockPort clock;
    private final SecureRandom random;
    private final Persistence persistence;

    public SetupSessionStore(ClockPort clock) { this(clock, new SecureRandom(), new InMemoryPersistence()); }

    public SetupSessionStore(ClockPort clock, SecureRandom random, Persistence persistence) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    public Issued issue(String provider) {
        String normalized = normalizeProvider(provider);
        Instant now = clock.now();
        String code = code();
        Instant expires = now.plus(TTL);
        persistence.save(new Session(normalized, hash(code), now, expires, false));
        return new Issued(normalized, code, expires);
    }

    public ConsumeResult consume(String provider, String code) {
        String normalized = normalizeProvider(provider);
        if (code == null || code.isBlank()) return new ConsumeResult(ConsumeStatus.MISSING, Optional.empty());
        String hash = hash(code.trim().toUpperCase(Locale.ROOT));
        Optional<Session> found = persistence.find(hash);
        if (found.isEmpty()) return new ConsumeResult(ConsumeStatus.MISSING, Optional.empty());
        Session session = found.get();
        if (!session.provider().equals(normalized)) return new ConsumeResult(ConsumeStatus.WRONG_PROVIDER, Optional.of(session));
        if (session.consumed()) return new ConsumeResult(ConsumeStatus.REPLAY, Optional.of(session));
        if (!session.expiresAt().isAfter(clock.now())) return new ConsumeResult(ConsumeStatus.EXPIRED, Optional.of(session));
        if (!persistence.consume(hash)) return new ConsumeResult(ConsumeStatus.REPLAY, Optional.of(session));
        return new ConsumeResult(ConsumeStatus.CONSUMED, Optional.of(session));
    }

    public void cancel(String provider) { persistence.cancelProvider(normalizeProvider(provider), clock.now()); }

    private String code() {
        char[] value = new char[8];
        for (int i = 0; i < value.length; i++) value[i] = CROCKFORD[random.nextInt(CROCKFORD.length)];
        return new String(value);
    }

    private static String hash(String code) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is required", error);
        }
    }

    private static String normalizeProvider(String provider) {
        String value = Objects.requireNonNull(provider, "provider").trim().toLowerCase(Locale.ROOT);
        if (!value.equals("discord") && !value.equals("telegram")) throw new IllegalArgumentException("unsupported provider");
        return value;
    }
}
