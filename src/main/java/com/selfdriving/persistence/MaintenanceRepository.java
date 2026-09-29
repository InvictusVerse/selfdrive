package com.selfdriving.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The maintenance log (what was done to the car and with what result) and system test runs. */
public final class MaintenanceRepository {

    /** A log entry. */
    public record Entry(long id, String technician, Long issueId, String action, String result, Instant at) {
    }

    /** One test in a run. */
    public record TestResult(String name, boolean passed, long durationMs, String message) {
    }

    /** A system test run with its results. */
    public record TestRun(long id, String technician, Instant startedAt, int passed, int failed,
                          List<TestResult> results) {
    }

    private final Jdbc jdbc;

    public MaintenanceRepository(Database db) {
        this.jdbc = new Jdbc(db);
    }

    public void log(Long technicianId, Long issueId, String action, String result, Instant at) {
        jdbc.update("INSERT INTO maintenance_log (technician_id, issue_id, action, result, created_at) "
                + "VALUES (?, ?, ?, ?, ?)", technicianId, issueId, AuditRepository.truncate(action, 200),
                AuditRepository.truncate(result, 200), at);
    }

    public List<Entry> log(int limit) {
        return jdbc.query("SELECT m.id, u.full_name, m.issue_id, m.action, m.result, m.created_at FROM maintenance_log m "
                + "LEFT JOIN users u ON u.id = m.technician_id ORDER BY m.created_at DESC, m.id DESC LIMIT ?",
                r -> new Entry(r.getLong(1), r.getString(2), Jdbc.nullableLong(r, "issue_id"), r.getString(4),
                        r.getString(5), Jdbc.instant(r, "created_at")), limit);
    }

    public long saveRun(Long technicianId, Instant startedAt, List<TestResult> results) {
        int passed = (int) results.stream().filter(TestResult::passed).count();
        long run = jdbc.insert("INSERT INTO test_runs (technician_id, started_at, passed, failed) VALUES (?, ?, ?, ?)",
                technicianId, startedAt, passed, results.size() - passed);
        for (TestResult t : results) {
            jdbc.update("INSERT INTO test_results (run_id, name, passed, duration_ms, message) VALUES (?, ?, ?, ?, ?)",
                    run, t.name(), t.passed(), t.durationMs(), AuditRepository.truncate(t.message(), 300));
        }
        return run;
    }

    /** Newest first, each with its results. */
    public List<TestRun> runs(int limit) {
        List<TestRun> runs = new ArrayList<>();
        for (Object[] row : jdbc.query("SELECT t.id, u.full_name, t.started_at, t.passed, t.failed FROM test_runs t "
                + "LEFT JOIN users u ON u.id = t.technician_id ORDER BY t.started_at DESC, t.id DESC LIMIT ?",
                r -> new Object[] {r.getLong(1), r.getString(2), Jdbc.instant(r, "started_at"), r.getInt(4), r.getInt(5)},
                limit)) {
            long id = (long) row[0];
            List<TestResult> results = jdbc.query("SELECT name, passed, duration_ms, message FROM test_results "
                    + "WHERE run_id = ? ORDER BY id", r -> new TestResult(r.getString(1), r.getBoolean(2), r.getLong(3),
                    r.getString(4)), id);
            runs.add(new TestRun(id, (String) row[1], (Instant) row[2], (int) row[3], (int) row[4], results));
        }
        return runs;
    }
}
