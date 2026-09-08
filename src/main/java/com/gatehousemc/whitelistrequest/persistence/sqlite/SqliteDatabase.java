package com.gatehousemc.whitelistrequest.persistence.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class SqliteDatabase implements AutoCloseable {
    private final Connection connection;

    public SqliteDatabase(Path path, int busyTimeoutMs) throws IOException, SQLException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        MigrationRunner.initialize(connection, busyTimeoutMs);
    }

    Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
