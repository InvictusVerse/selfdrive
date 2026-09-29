package com.selfdriving.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.Role;
import com.selfdriving.auth.User;

/** User accounts. Password hashes are read and written only here. */
public final class UserRepository {

    private static final String COLUMNS = "id, username, full_name, role, access_level, active, failed_attempts, "
            + "locked_until, last_login, created_at";

    private final Jdbc jdbc;

    public UserRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    private static User map(ResultSet r) throws SQLException {
        return new User(r.getLong("id"), r.getString("username"), r.getString("full_name"),
                Role.valueOf(r.getString("role")), AccessLevel.valueOf(r.getString("access_level")),
                r.getBoolean("active"), r.getInt("failed_attempts"), Jdbc.instant(r, "locked_until"),
                Jdbc.instant(r, "last_login"), Jdbc.instant(r, "created_at"));
    }

    public List<User> all() {
        return jdbc.query("SELECT " + COLUMNS + " FROM users ORDER BY role, username", UserRepository::map);
    }

    public Optional<User> find(long id) {
        return jdbc.one("SELECT " + COLUMNS + " FROM users WHERE id = ?", UserRepository::map, id);
    }

    /** Usernames are compared without regard to case. */
    public Optional<User> findByUsername(String username) {
        return jdbc.one("SELECT " + COLUMNS + " FROM users WHERE LOWER(username) = LOWER(?)", UserRepository::map,
                username);
    }

    public String passwordHash(long id) {
        return jdbc.one("SELECT password_hash FROM users WHERE id = ?", r -> r.getString(1), id).orElse(null);
    }

    public long create(String username, String fullName, String passwordHash, Role role, AccessLevel level) {
        return jdbc.insert("INSERT INTO users (username, full_name, password_hash, role, access_level) "
                + "VALUES (?, ?, ?, ?, ?)", username, fullName, passwordHash, role, level);
    }

    public void update(long id, String fullName, Role role, AccessLevel level, boolean active) {
        jdbc.update("UPDATE users SET full_name = ?, role = ?, access_level = ?, active = ?, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?", fullName, role, level, active, id);
    }

    public void setPassword(long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash = ?, failed_attempts = 0, locked_until = NULL, "
                + "updated_at = CURRENT_TIMESTAMP WHERE id = ?", passwordHash, id);
    }

    public void recordFailure(long id, int failedAttempts, Instant lockedUntil) {
        jdbc.update("UPDATE users SET failed_attempts = ?, locked_until = ? WHERE id = ?", failedAttempts,
                lockedUntil, id);
    }

    public void recordLogin(long id, Instant at) {
        jdbc.update("UPDATE users SET failed_attempts = 0, locked_until = NULL, last_login = ? WHERE id = ?", at, id);
    }

    public void delete(long id) {
        jdbc.update("DELETE FROM users WHERE id = ?", id);
    }

    public int count() {
        return jdbc.one("SELECT COUNT(*) FROM users", r -> r.getInt(1)).orElse(0);
    }

    /** Active admins with full access (the last one cannot be removed or demoted). */
    public int activeFullAdmins() {
        return jdbc.one("SELECT COUNT(*) FROM users WHERE role = 'ADMIN' AND access_level = 'FULL' AND active = TRUE",
                r -> r.getInt(1)).orElse(0);
    }
}
