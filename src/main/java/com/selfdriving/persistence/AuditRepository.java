package com.selfdriving.persistence;

import java.time.Instant;
import java.util.List;

/** Who did what and when: logins, user changes, settings, updates, fixes. */
public final class AuditRepository {

    /** One entry. */
    public record Entry(long id, Long userId, String username, String action, String details, Instant at) {
    }

    private final Jdbc jdbc;

    public AuditRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    public void log(Long userId, String username, String action, String details) {
        jdbc.update("INSERT INTO audit_log (user_id, username, action, details) VALUES (?, ?, ?, ?)", userId, username,
                action, details == null ? null : truncate(details, 300));
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT id, user_id, username, action, details, created_at FROM audit_log "
                + "ORDER BY created_at DESC, id DESC LIMIT ?", r -> new Entry(r.getLong("id"),
                Jdbc.nullableLong(r, "user_id"), r.getString("username"), r.getString("action"), r.getString("details"),
                Jdbc.instant(r, "created_at")), limit);
    }

    static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
