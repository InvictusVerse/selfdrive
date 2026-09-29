package com.selfdriving.persistence;

import java.time.Instant;
import java.util.List;

import com.selfdriving.alerts.Alert;

/** Every alert raised, with who acknowledged it and when. */
public final class AlertRepository {

    /** A stored alert. */
    public record Stored(long id, Alert.Severity severity, Alert.Category category, String message, String source,
                         Instant createdAt, String acknowledgedBy, Instant acknowledgedAt) {
    }

    private final Jdbc jdbc;

    public AlertRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    public long insert(Alert alert, Instant at) {
        return jdbc.insert("INSERT INTO alerts (severity, category, message, source, created_at) VALUES (?, ?, ?, ?, ?)",
                alert.severity(), alert.category(), AuditRepository.truncate(alert.message(), 300),
                AuditRepository.truncate(alert.source(), 60), at);
    }

    public void acknowledge(long id, Long userId, Instant at) {
        jdbc.update("UPDATE alerts SET acknowledged_by = ?, acknowledged_at = ? WHERE id = ? AND acknowledged_at IS NULL",
                userId, at, id);
    }

    /**
     * Newest first.
     *
     * @param minSeverity lowest severity to include
     * @param category    only this category, or null for all
     */
    public List<Stored> recent(int limit, Alert.Severity minSeverity, Alert.Category category) {
        List<String> severities = switch (minSeverity) {
            case CRITICAL -> List.of("CRITICAL");
            case WARNING -> List.of("WARNING", "CRITICAL");
            default -> List.of("INFO", "WARNING", "CRITICAL");
        };
        String categoryFilter = category == null ? "" : " AND a.category = ?";
        String sql = "SELECT a.id, a.severity, a.category, a.message, a.source, a.created_at, u.username, "
                + "a.acknowledged_at FROM alerts a LEFT JOIN users u ON u.id = a.acknowledged_by "
                + "WHERE a.severity IN (?, ?, ?)" + categoryFilter + " ORDER BY a.created_at DESC, a.id DESC LIMIT ?";
        Object[] args = new Object[category == null ? 4 : 5];
        for (int i = 0; i < 3; i++) {
            args[i] = severities.get(Math.min(i, severities.size() - 1));
        }
        int next = 3;
        if (category != null) {
            args[next++] = category;
        }
        args[next] = limit;
        return jdbc.query(sql, r -> new Stored(r.getLong(1), Alert.Severity.valueOf(r.getString(2)),
                Alert.Category.valueOf(r.getString(3)), r.getString(4), r.getString(5), Jdbc.instant(r, "created_at"),
                r.getString(7), Jdbc.instant(r, "acknowledged_at")), args);
    }

    /** Alerts per severity since a time: {info, warning, critical}. */
    public int[] countsSince(Instant since) {
        int[] counts = new int[3];
        for (Object[] row : jdbc.query("SELECT severity, COUNT(*) FROM alerts WHERE created_at >= ? GROUP BY severity",
                r -> new Object[] {r.getString(1), r.getInt(2)}, since)) {
            counts[Alert.Severity.valueOf((String) row[0]).ordinal()] = (int) row[1];
        }
        return counts;
    }
}
