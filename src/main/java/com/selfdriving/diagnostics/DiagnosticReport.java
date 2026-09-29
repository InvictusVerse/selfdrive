package com.selfdriving.diagnostics;

import java.util.List;

/**
 * Result of a diagnostic scan: one line per subsystem.
 *
 * @param simulationTime when the scan ran, s
 * @param checks         every subsystem, in order
 */
public record DiagnosticReport(double simulationTime, List<Check> checks) {

    /** Health of one subsystem. */
    public enum Status { OK, WARNING, FAULT }

    /**
     * @param subsystem what was checked
     * @param status    result
     * @param reading   the measurement behind it, for the technician
     * @param fault     the fault found, or null
     */
    public record Check(Subsystem subsystem, Status status, String reading, Fault fault) {
    }

    public DiagnosticReport {
        checks = List.copyOf(checks);
    }

    public List<Fault> faults() {
        return checks.stream().map(Check::fault).filter(f -> f != null).toList();
    }

    public boolean healthy() {
        return checks.stream().allMatch(c -> c.status() == Status.OK);
    }
}
