package com.gatehousemc.whitelistrequest.persistence.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Transaction and connection helper for SQLite operations.
 * Centralizes commit/rollback/auto-commit management so callers
 * don't repeat boilerplate.
 */
final class TransactionHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransactionHelper.class);

    private final Connection connection;

    TransactionHelper(Connection connection) {
        this.connection = connection;
    }

    void begin() throws SQLException {
        connection.setAutoCommit(false);
    }

    void commit() throws SQLException {
        connection.commit();
    }

    void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException ignored) {}
    }

    void resetAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException ignored) {}
    }

    Connection connection() {
        return connection;
    }
}
