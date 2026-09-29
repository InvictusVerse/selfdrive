package com.selfdriving.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Software updates offered to the car and what happened when they were installed. */
public final class UpdateRepository {

    /** Life of an update. */
    public enum Status { AVAILABLE, DOWNLOADING, INSTALLING, COMPLETED, FAILED, ROLLED_BACK }

    /** A stored update. */
    public record Update(long id, String version, String notes, Status status, int progress, Instant startedAt,
                         Instant finishedAt, String deployedBy, String detail, String previousValues) {
    }

    private static final String SELECT = "SELECT s.id, s.version, s.notes, s.status, s.progress, s.started_at, "
            + "s.finished_at, u.full_name, s.detail, s.previous_values FROM software_updates s "
            + "LEFT JOIN users u ON u.id = s.deployed_by ";

    private final Jdbc jdbc;

    public UpdateRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    private static Update map(ResultSet r) throws SQLException {
        return new Update(r.getLong(1), r.getString(2), r.getString(3), Status.valueOf(r.getString(4)), r.getInt(5),
                Jdbc.instant(r, "started_at"), Jdbc.instant(r, "finished_at"), r.getString(8), r.getString(9),
                r.getString(10));
    }

    /** Adds an update offer unless that version is already known. */
    public void offer(String version, String notes) {
        if (byVersion(version).isEmpty()) {
            jdbc.update("INSERT INTO software_updates (version, notes, status) VALUES (?, ?, 'AVAILABLE')", version,
                    AuditRepository.truncate(notes, 500));
        }
    }

    public Optional<Update> find(long id) {
        return jdbc.one(SELECT + "WHERE s.id = ?", UpdateRepository::map, id);
    }

    public Optional<Update> byVersion(String version) {
        return jdbc.one(SELECT + "WHERE s.version = ?", UpdateRepository::map, version);
    }

    /** All updates, by version. */
    public List<Update> all() {
        return jdbc.query(SELECT + "ORDER BY s.version", UpdateRepository::map);
    }

    public void start(long id, Long userId, Instant at) {
        jdbc.update("UPDATE software_updates SET status = 'DOWNLOADING', progress = 0, started_at = ?, finished_at = NULL, "
                + "deployed_by = ?, detail = NULL WHERE id = ?", at, userId, id);
    }

    public void progress(long id, Status status, int progress) {
        jdbc.update("UPDATE software_updates SET status = ?, progress = ? WHERE id = ?", status, progress, id);
    }

    public void finish(long id, Status status, String detail, String previousValues, Instant at) {
        jdbc.update("UPDATE software_updates SET status = ?, detail = ?, previous_values = ?, finished_at = ? "
                + "WHERE id = ?", status, detail == null ? null : AuditRepository.truncate(detail, 300),
                previousValues, at, id);
    }
}
