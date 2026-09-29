package com.selfdriving.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Issues found in the car and their progress from report to closure. */
public final class IssueRepository {

    /** Where an issue is in its life. */
    public enum Status { OPEN, IN_PROGRESS, FIXED, VERIFIED, CLOSED }

    /** A stored issue. {@code faultCode} is "USER" for issues reported by hand. */
    public record Issue(long id, String faultCode, String subsystem, String severity, String description, Status status,
                        Instant detectedAt, Long assignedTo, String assignedName, String resolution,
                        Instant resolvedAt) {
    }

    public static final String MANUAL_CODE = "USER";

    private static final String SELECT = "SELECT i.id, i.fault_code, i.subsystem, i.severity, i.description, i.status, "
            + "i.detected_at, i.assigned_to, u.full_name, i.resolution, i.resolved_at FROM issues i "
            + "LEFT JOIN users u ON u.id = i.assigned_to ";

    private final Jdbc jdbc;

    public IssueRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    private static Issue map(ResultSet r) throws SQLException {
        return new Issue(r.getLong(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                Status.valueOf(r.getString(6)), Jdbc.instant(r, "detected_at"), Jdbc.nullableLong(r, "assigned_to"),
                r.getString(9), r.getString(10), Jdbc.instant(r, "resolved_at"));
    }

    public long create(String faultCode, String subsystem, String severity, String description, Instant at) {
        return jdbc.insert("INSERT INTO issues (fault_code, subsystem, severity, description, status, detected_at) "
                + "VALUES (?, ?, ?, ?, 'OPEN', ?)", faultCode, subsystem, severity,
                AuditRepository.truncate(description, 300), at);
    }

    public Optional<Issue> find(long id) {
        return jdbc.one(SELECT + "WHERE i.id = ?", IssueRepository::map, id);
    }

    /** The issue for a fault code that has not been closed yet, if any. */
    public Optional<Issue> findUnclosed(String faultCode) {
        return jdbc.one(SELECT + "WHERE i.fault_code = ? AND i.status <> 'CLOSED' ORDER BY i.id DESC LIMIT 1",
                IssueRepository::map, faultCode);
    }

    /** Newest first; closed issues only when asked for. */
    public List<Issue> list(boolean includeClosed, int limit) {
        return jdbc.query(SELECT + (includeClosed ? "" : "WHERE i.status <> 'CLOSED' ")
                + "ORDER BY i.detected_at DESC, i.id DESC LIMIT ?", IssueRepository::map, limit);
    }

    public int countUnclosed() {
        return jdbc.one("SELECT COUNT(*) FROM issues WHERE status <> 'CLOSED'", r -> r.getInt(1)).orElse(0);
    }

    public void update(long id, Status status, Long assignedTo, String resolution, Instant resolvedAt) {
        jdbc.update("UPDATE issues SET status = ?, assigned_to = ?, resolution = ?, resolved_at = ? WHERE id = ?",
                status, assignedTo, resolution == null ? null : AuditRepository.truncate(resolution, 300), resolvedAt,
                id);
    }
}
