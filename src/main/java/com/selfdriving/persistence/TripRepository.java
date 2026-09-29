package com.selfdriving.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** Trips: where, how far, how long, how much energy, and how much of it on autopilot. */
public final class TripRepository {

    /** A stored trip. Fields for the result are null while it is in progress. */
    public record Trip(long id, Long driverId, String driverName, String origin, String destination, double plannedM,
                       double etaS, Double drivenM, Double durationS, Double energyKwh, Double autopilotShare,
                       String status, Instant startedAt, Instant endedAt) {
    }

    private static final String SELECT = "SELECT t.id, t.driver_id, u.full_name, t.origin, t.destination, t.planned_m, "
            + "t.eta_s, t.driven_m, t.duration_s, t.energy_kwh, t.autopilot_share, t.status, t.started_at, t.ended_at "
            + "FROM trips t LEFT JOIN users u ON u.id = t.driver_id ";

    private final Jdbc jdbc;

    public TripRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    private static Trip map(ResultSet r) throws SQLException {
        return new Trip(r.getLong(1), Jdbc.nullableLong(r, "driver_id"), r.getString(3), r.getString(4), r.getString(5),
                r.getDouble(6), r.getDouble(7), Jdbc.nullableDouble(r, "driven_m"), Jdbc.nullableDouble(r, "duration_s"),
                Jdbc.nullableDouble(r, "energy_kwh"), Jdbc.nullableDouble(r, "autopilot_share"), r.getString(12),
                Jdbc.instant(r, "started_at"), Jdbc.instant(r, "ended_at"));
    }

    public long start(Long driverId, String origin, String destination, double plannedM, double etaS, Instant at) {
        return jdbc.insert("INSERT INTO trips (driver_id, origin, destination, planned_m, eta_s, status, started_at) "
                + "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', ?)", driverId, origin, destination, plannedM, etaS, at);
    }

    public void finish(long id, double drivenM, double durationS, double energyKwh, double autopilotShare,
                       String status, Instant at) {
        jdbc.update("UPDATE trips SET driven_m = ?, duration_s = ?, energy_kwh = ?, autopilot_share = ?, status = ?, "
                + "ended_at = ? WHERE id = ?", drivenM, durationS, energyKwh, autopilotShare, status, at, id);
    }

    public List<Trip> forDriver(long driverId, int limit) {
        return jdbc.query(SELECT + "WHERE t.driver_id = ? ORDER BY t.started_at DESC, t.id DESC LIMIT ?",
                TripRepository::map, driverId, limit);
    }

    public List<Trip> all(int limit) {
        return jdbc.query(SELECT + "ORDER BY t.started_at DESC, t.id DESC LIMIT ?", TripRepository::map, limit);
    }

    /** Totals over finished trips. */
    public record Totals(int trips, double drivenM, double energyKwh) {
    }

    /** Trips started since a time (all of them for {@code Instant.EPOCH}). */
    public Totals totals(Instant since) {
        return jdbc.one("SELECT COUNT(*), COALESCE(SUM(driven_m), 0), COALESCE(SUM(energy_kwh), 0) FROM trips "
                + "WHERE started_at >= ?", r -> new Totals(r.getInt(1), r.getDouble(2), r.getDouble(3)), since)
                .orElse(new Totals(0, 0, 0));
    }

    /** Trips that were left unfinished (the app closed during a trip): marked as cancelled. */
    public void closeUnfinished(Instant at) {
        jdbc.update("UPDATE trips SET status = 'CANCELLED', ended_at = ? WHERE status = 'IN_PROGRESS'", at);
    }
}
