package com.selfdriving.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Small helpers so repositories stay short: every call takes SQL with {@code ?} placeholders
 * and the values separately (bound with {@link PreparedStatement}), opens a connection, runs,
 * and closes everything.
 */
final class Jdbc {

    /** Reads one row. */
    @FunctionalInterface
    interface Row<T> {
        T map(ResultSet r) throws SQLException;
    }

    private final Database db;

    Jdbc(Database db) {
        this.db = db;
    }

    <T> List<T> query(String sql, Row<T> row, Object... args) {
        try (Connection c = db.connect(); PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            try (ResultSet r = p.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (r.next()) {
                    result.add(row.map(r));
                }
                return result;
            }
        } catch (SQLException e) {
            throw new DataException("Database query failed", e);
        }
    }

    <T> Optional<T> one(String sql, Row<T> row, Object... args) {
        List<T> list = query(sql, row, args);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    int update(String sql, Object... args) {
        try (Connection c = db.connect(); PreparedStatement p = c.prepareStatement(sql)) {
            bind(p, args);
            return p.executeUpdate();
        } catch (SQLException e) {
            throw new DataException("Database update failed", e);
        }
    }

    /** Runs an INSERT and returns the generated id. */
    long insert(String sql, Object... args) {
        try (Connection c = db.connect(); PreparedStatement p = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(p, args);
            p.executeUpdate();
            try (ResultSet keys = p.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No id returned");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new DataException("Database insert failed", e);
        }
    }

    private static void bind(PreparedStatement p, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a == null) {
                p.setNull(i + 1, Types.NULL);
            } else if (a instanceof Instant instant) {
                p.setTimestamp(i + 1, Timestamp.from(instant));
            } else if (a instanceof Enum<?> e) {
                p.setString(i + 1, e.name());
            } else {
                p.setObject(i + 1, a);
            }
        }
    }

    static Instant instant(ResultSet r, String column) throws SQLException {
        Timestamp t = r.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    static Long nullableLong(ResultSet r, String column) throws SQLException {
        long v = r.getLong(column);
        return r.wasNull() ? null : v;
    }

    static Double nullableDouble(ResultSet r, String column) throws SQLException {
        double v = r.getDouble(column);
        return r.wasNull() ? null : v;
    }
}
