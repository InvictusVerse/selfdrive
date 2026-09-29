package com.selfdriving.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.selfdriving.auth.Permission;
import com.selfdriving.diagnostics.DiagnosticReport;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.IssueRepository;
import com.selfdriving.persistence.IssueRepository.Issue;
import com.selfdriving.persistence.IssueRepository.Status;
import com.selfdriving.persistence.MaintenanceRepository;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SystemTests;

/**
 * The technician's work: diagnostic scans, fault injection, fixes, system tests, issues and
 * the maintenance log.
 *
 * <p>Issue workflow: a scan opens an issue for each fault found (one per fault code until it is
 * closed). OPEN → IN_PROGRESS (someone takes it) → FIXED (the repair is applied) → VERIFIED (a
 * scan confirms the fault is gone; if it is not, the issue goes back to OPEN) → CLOSED.
 */
public final class MaintenanceService {

    /** A scan and the issues it opened. */
    public record ScanResult(DiagnosticReport report, List<Issue> opened) {
    }

    private final Simulation simulation;
    private final IssueRepository issues;
    private final MaintenanceRepository log;
    private final AuditRepository audit;
    private final Clock clock;

    public MaintenanceService(Simulation simulation, IssueRepository issues, MaintenanceRepository log,
                              AuditRepository audit, Clock clock) {
        this.simulation = simulation;
        this.issues = issues;
        this.log = log;
        this.audit = audit;
        this.clock = clock;
    }

    // ---- Diagnostics and faults -------------------------------------------------------------

    public ScanResult scan(Session session) {
        session.require(Permission.RUN_DIAGNOSTICS);
        DiagnosticReport report = OnSimulation.call(simulation, Simulation::diagnose);
        Instant now = clock.instant();
        List<Issue> opened = new ArrayList<>();
        for (DiagnosticReport.Check c : report.checks()) {
            Fault f = c.fault();
            if (f != null && issues.findUnclosed(f.code()).isEmpty()) {
                long id = issues.create(f.code(), f.subsystem().label(), f.severity().name(),
                        f.title() + ". " + f.effect(), now);
                opened.add(issues.find(id).orElseThrow());
            }
        }
        int found = report.faults().size();
        log.log(session.userId(), null, "Diagnostic scan", found == 0 ? "All systems OK"
                : found + " fault(s): " + String.join(", ", report.faults().stream().map(Fault::code).toList()), now);
        return new ScanResult(report, opened);
    }

    /** Makes a fault appear in the car, to test how it and the people using it react (full access). */
    public void injectFault(Session session, Fault fault) {
        session.require(Permission.INJECT_FAULTS);
        OnSimulation.run(simulation, s -> s.injectFault(fault));
        audit.log(session.userId(), session.user().username(), "FAULT_INJECTED", fault.code() + " " + fault.title());
        log.log(session.userId(), null, "Injected fault " + fault.code(), fault.title(), clock.instant());
    }

    public Set<Fault> activeFaults() {
        return simulation.latest().faults();
    }

    // ---- Issues -----------------------------------------------------------------------------

    public List<Issue> issues(Session session, boolean includeClosed) {
        session.require(Permission.MANAGE_ISSUES);
        return issues.list(includeClosed, 500);
    }

    /** An issue noticed by a person (no fault code). */
    public Issue report(Session session, String subsystem, String description) {
        session.require(Permission.MANAGE_ISSUES);
        String text = description == null ? "" : description.trim();
        if (text.length() < 5 || text.length() > 300) {
            throw new ValidationException("Describe the issue in 5 to 300 characters");
        }
        long id = issues.create(IssueRepository.MANUAL_CODE, subsystem, "WARNING", text, clock.instant());
        log.log(session.userId(), id, "Issue reported", text, clock.instant());
        return issues.find(id).orElseThrow();
    }

    /** OPEN → IN_PROGRESS, assigned to the user. */
    public Issue startWork(Session session, long id) {
        session.require(Permission.MANAGE_ISSUES);
        Issue i = expect(id, Status.OPEN);
        issues.update(id, Status.IN_PROGRESS, session.userId(), i.resolution(), null);
        log.log(session.userId(), id, "Started work on " + label(i), "In progress", clock.instant());
        return issues.find(id).orElseThrow();
    }

    /**
     * IN_PROGRESS → FIXED. For a fault the repair is applied to the car (the fault clears); for a
     * reported issue the note says what was done.
     */
    public Issue fix(Session session, long id, String note) {
        session.require(Permission.APPLY_FIXES);
        Issue i = expect(id, Status.IN_PROGRESS);
        String resolution;
        if (IssueRepository.MANUAL_CODE.equals(i.faultCode())) {
            resolution = note == null ? "" : note.trim();
            if (resolution.length() < 3) {
                throw new ValidationException("Say what was done to fix it");
            }
        } else {
            Fault fault = Fault.byCode(i.faultCode());
            OnSimulation.run(simulation, s -> s.clearFault(fault));
            resolution = fault.fix();
        }
        Instant now = clock.instant();
        issues.update(id, Status.FIXED, i.assignedTo() == null ? session.userId() : i.assignedTo(), resolution, now);
        log.log(session.userId(), id, "Fixed " + label(i), resolution, now);
        audit.log(session.userId(), session.user().username(), "FIX_APPLIED", label(i) + ": " + resolution);
        return issues.find(id).orElseThrow();
    }

    /**
     * FIXED → VERIFIED when a fresh scan shows the fault is gone; otherwise back to OPEN.
     *
     * @return the issue after the check
     */
    public Issue verify(Session session, long id) {
        session.require(Permission.MANAGE_ISSUES);
        Issue i = expect(id, Status.FIXED);
        boolean stillThere = false;
        if (!IssueRepository.MANUAL_CODE.equals(i.faultCode())) {
            Fault fault = Fault.byCode(i.faultCode());
            stillThere = OnSimulation.call(simulation, Simulation::diagnose).faults().contains(fault);
        }
        Instant now = clock.instant();
        if (stillThere) {
            issues.update(id, Status.OPEN, i.assignedTo(), i.resolution(), null);
            log.log(session.userId(), id, "Verification of " + label(i), "Failed: fault still present, reopened", now);
        } else {
            issues.update(id, Status.VERIFIED, i.assignedTo(), i.resolution(), i.resolvedAt());
            log.log(session.userId(), id, "Verification of " + label(i), "Passed", now);
        }
        return issues.find(id).orElseThrow();
    }

    /** VERIFIED → CLOSED. */
    public Issue close(Session session, long id) {
        session.require(Permission.MANAGE_ISSUES);
        Issue i = expect(id, Status.VERIFIED);
        issues.update(id, Status.CLOSED, i.assignedTo(), i.resolution(), i.resolvedAt());
        log.log(session.userId(), id, "Closed " + label(i), "Closed", clock.instant());
        return issues.find(id).orElseThrow();
    }

    private Issue expect(long id, Status status) {
        Issue i = issues.find(id).orElseThrow(() -> new ValidationException("No such issue"));
        if (i.status() != status) {
            throw new ValidationException("Issue #" + id + " is " + i.status().name().toLowerCase().replace('_', ' ')
                    + "; this step needs it to be " + status.name().toLowerCase().replace('_', ' '));
        }
        return i;
    }

    private static String label(Issue i) {
        return "#" + i.id() + (IssueRepository.MANUAL_CODE.equals(i.faultCode()) ? "" : " " + i.faultCode());
    }

    // ---- System tests and log ---------------------------------------------------------------

    /** Runs every system test against a copy of the car with its current faults, and saves the run. */
    public MaintenanceRepository.TestRun runSystemTests(Session session) {
        session.require(Permission.RUN_SYSTEM_TESTS);
        Instant started = clock.instant();
        List<SystemTests.Result> results = new SystemTests(simulation.world(), simulation.params(),
                simulation.latest().faults()).runAll();
        List<MaintenanceRepository.TestResult> stored = results.stream()
                .map(r -> new MaintenanceRepository.TestResult(r.name(), r.passed(), r.durationMs(), r.message()))
                .toList();
        long runId = log.saveRun(session.userId(), started, stored);
        long passed = results.stream().filter(SystemTests.Result::passed).count();
        log.log(session.userId(), null, "System tests (run #" + runId + ")",
                passed + " of " + results.size() + " passed", clock.instant());
        return log.runs(1).get(0);
    }

    public List<MaintenanceRepository.TestRun> testRuns(Session session, int limit) {
        session.require(Permission.RUN_SYSTEM_TESTS);
        return log.runs(limit);
    }

    public List<MaintenanceRepository.Entry> maintenanceLog(Session session, int limit) {
        if (!session.can(Permission.MANAGE_ISSUES)) {
            session.require(Permission.RUN_DIAGNOSTICS);
        }
        return log.log(limit);
    }
}
