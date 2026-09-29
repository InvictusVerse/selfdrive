package com.selfdriving.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The embedded H2 database: one file on this PC, no server.
 *
 * <p>On opening, the schema is brought up to date by running the versioned scripts in
 * {@code /db} (listed in {@code migrations.txt}) that have not run yet; each is recorded in
 * {@code schema_version}. Every query in the app goes through {@link PreparedStatement}s with
 * parameters, never string concatenation, so user input cannot change the SQL.
 *
 * <p>H2 connections are cheap in embedded mode; each repository call opens and closes one.
 */
public final class Database {

    private static final System.Logger LOG = System.getLogger(Database.class.getName());

    /** System property to choose the data folder. */
    public static final String DATA_DIR_PROPERTY = "selfdrive.dataDir";

    private final String url;
    private Connection keeper;

    private Database(String url) {
        this.url = url;
    }

    /** The database file in the app's data folder (created if needed), migrated. */
    public static Database openDefault() {
        Path dir = dataDirectory();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the data folder " + dir, e);
        }
        String path = dir.resolve("selfdriving").toAbsolutePath().toString().replace('\\', '/');
        Database db = new Database("jdbc:h2:file:" + path + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        db.migrate();
        db.keepOpen();
        LOG.log(Level.INFO, "Database at {0}.mv.db", path);
        return db;
    }

    /** A private in-memory database (tests). */
    public static Database inMemory(String name) {
        Database db = new Database("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        db.migrate();
        return db;
    }

    /**
     * Where data lives: the {@value #DATA_DIR_PROPERTY} property if set; for the installed app
     * the user's local application-data folder (the install folder may be read-only); otherwise
     * {@code data/} next to where the app was started.
     */
    public static Path dataDirectory() {
        String configured = System.getProperty(DATA_DIR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String local = System.getenv("LOCALAPPDATA");
        if (System.getProperty("jpackage.app-path") != null && local != null) {
            return Path.of(local, "SelfDrive", "data");
        }
        return Path.of("data");
    }

    public String url() {
        return url;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    /**
     * H2 closes a file database when its last connection closes, and opening it again is slow.
     * One idle connection held for the app's lifetime keeps it open; {@link #close()} releases it.
     */
    private void keepOpen() {
        try {
            keeper = connect();
        } catch (SQLException e) {
            throw new DataException("Could not open the database", e);
        }
    }

    /** Closes the database (the app is shutting down). */
    public void close() {
        if (keeper != null) {
            try {
                keeper.close();
            } catch (SQLException e) {
                LOG.log(Level.WARNING, "Closing the database failed", e);
            }
            keeper = null;
        }
    }

    // ---- migrations ---------------------------------------------------------------------------

    private void migrate() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (script VARCHAR(120) PRIMARY KEY, "
                    + "applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
            for (String script : scripts()) {
                if (applied(c, script)) {
                    continue;
                }
                c.setAutoCommit(false);
                try {
                    for (String sql : statements(read("/db/" + script))) {
                        s.execute(sql);
                    }
                    try (PreparedStatement p = c.prepareStatement("INSERT INTO schema_version (script) VALUES (?)")) {
                        p.setString(1, script);
                        p.executeUpdate();
                    }
                    c.commit();
                    LOG.log(Level.INFO, "Applied database script {0}", script);
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new DataException("Could not prepare the database", e);
        }
    }

    private static boolean applied(Connection c, String script) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM schema_version WHERE script = ?")) {
            p.setString(1, script);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    private static List<String> scripts() {
        List<String> result = new ArrayList<>();
        for (String line : read("/db/migrations.txt").split("\\R")) {
            if (!line.isBlank()) {
                result.add(line.trim());
            }
        }
        return result;
    }

    /** Splits a script into statements (on semicolons at line ends), dropping comments. */
    static List<String> statements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : script.split("\\R")) {
            int comment = line.indexOf("--");
            String code = comment >= 0 ? line.substring(0, comment) : line;
            if (code.isBlank()) {
                continue;
            }
            current.append(code).append('\n');
            if (code.trim().endsWith(";")) {
                String sql = current.toString().trim();
                result.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private static String read(String resource) {
        try (InputStream in = Database.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
