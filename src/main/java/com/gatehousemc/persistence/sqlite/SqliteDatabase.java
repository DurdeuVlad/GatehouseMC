package com.gatehousemc.persistence.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.StandardCopyOption;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;

public final class SqliteDatabase implements AutoCloseable {
    private static volatile boolean driverInitialized = false;
    private final Connection connection;

    public SqliteDatabase(Path path, int busyTimeoutMs) throws IOException, SQLException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        ensureDriverLoaded();
        Connection opened = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        try {
            MigrationRunner.initialize(opened, busyTimeoutMs);
        } catch (SQLException | RuntimeException error) {
            try {
                opened.close();
            } catch (SQLException closeError) {
                error.addSuppressed(closeError);
            }
            throw error;
        }
        connection = opened;
    }

    Connection connection() {
        return connection;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static synchronized void ensureDriverLoaded() {
        if (driverInitialized) {
            return;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            driverInitialized = true;
            return;
        } catch (ClassNotFoundException ignored) {
        }

        try (InputStream in = SqliteDatabase.class.getResourceAsStream("/META-INF/libraries/sqlite-jdbc.jar")) {
            if (in == null) {
                return;
            }
            Path tempDir = Path.of(System.getProperty("java.io.tmpdir", "/tmp"), "gatehousemc-libs");
            Files.createDirectories(tempDir);
            Path tempJar = tempDir.resolve("sqlite-jdbc.jar");
            if (!Files.exists(tempJar) || Files.size(tempJar) == 0) {
                Files.copy(in, tempJar, StandardCopyOption.REPLACE_EXISTING);
            }
            URLClassLoader ucl = new URLClassLoader(new URL[]{tempJar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Class<?> clazz = Class.forName("org.sqlite.JDBC", true, ucl);
            Driver driver = (Driver) clazz.getDeclaredConstructor().newInstance();
            DriverManager.registerDriver(new DriverShim(driver));
            driverInitialized = true;
        } catch (Exception ignored) {
        }
    }

    private static final class DriverShim implements Driver {
        private final Driver driver;

        DriverShim(Driver driver) {
            this.driver = driver;
        }

        @Override
        public boolean acceptsURL(String url) throws SQLException {
            return driver.acceptsURL(url);
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return driver.connect(url, info);
        }

        @Override
        public int getMajorVersion() {
            return driver.getMajorVersion();
        }

        @Override
        public int getMinorVersion() {
            return driver.getMinorVersion();
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException {
            return driver.getPropertyInfo(url, info);
        }

        @Override
        public boolean jdbcCompliant() {
            return driver.jdbcCompliant();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return driver.getParentLogger();
        }
    }
}
