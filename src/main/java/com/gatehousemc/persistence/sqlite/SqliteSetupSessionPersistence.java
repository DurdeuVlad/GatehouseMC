package com.gatehousemc.persistence.sqlite;

import com.gatehousemc.application.SetupSessionStore;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/** SQLite persistence for one-time setup sessions. Only code hashes are stored. */
public final class SqliteSetupSessionPersistence implements SetupSessionStore.Persistence {
    private final SqliteDatabase database;

    public SqliteSetupSessionPersistence(SqliteDatabase database) {
        this.database = database;
    }

    @Override
    public synchronized void save(SetupSessionStore.Session session) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "INSERT INTO provider_setup_sessions(provider, code_hash, created_at, expires_at, consumed) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, session.provider());
            statement.setString(2, session.codeHash());
            statement.setLong(3, session.createdAt().toEpochMilli());
            statement.setLong(4, session.expiresAt().toEpochMilli());
            statement.setInt(5, session.consumed() ? 1 : 0);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Could not persist setup session", error);
        }
    }

    @Override
    public synchronized Optional<SetupSessionStore.Session> find(String codeHash) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT provider, code_hash, created_at, expires_at, consumed " +
                        "FROM provider_setup_sessions WHERE code_hash = ?")) {
            statement.setString(1, codeHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new SetupSessionStore.Session(
                        result.getString("provider"),
                        result.getString("code_hash"),
                        Instant.ofEpochMilli(result.getLong("created_at")),
                        Instant.ofEpochMilli(result.getLong("expires_at")),
                        result.getInt("consumed") != 0));
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not read setup session", error);
        }
    }

    @Override
    public synchronized boolean consume(String codeHash) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE provider_setup_sessions SET consumed = 1 WHERE code_hash = ? AND consumed = 0")) {
            statement.setString(1, codeHash);
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            throw new IllegalStateException("Could not consume setup session", error);
        }
    }

    @Override
    public synchronized void cancelProvider(String provider, Instant now) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "UPDATE provider_setup_sessions SET consumed = 1 " +
                        "WHERE provider = ? AND consumed = 0 AND expires_at > ?")) {
            statement.setString(1, provider);
            statement.setLong(2, now.toEpochMilli());
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new IllegalStateException("Could not cancel setup sessions", error);
        }
    }
}
