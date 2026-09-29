package com.selfdriving.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.Permission;
import com.selfdriving.auth.User;
import com.selfdriving.persistence.AlertRepository;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.IssueRepository;
import com.selfdriving.persistence.MaintenanceRepository;
import com.selfdriving.persistence.TripRepository;
import com.selfdriving.persistence.UpdateRepository;
import com.selfdriving.persistence.UserRepository;

/** Figures for the administrator's overview. */
public final class DashboardService {

    /**
     * @param softwareVersion  installed version
     * @param updatesAvailable updates offered and not installed
     * @param users            accounts
     * @param lockedUsers      accounts locked after wrong passwords right now
     * @param today            trips started today
     * @param allTime          all trips
     * @param alerts24h        alerts in the last 24 h: {info, warning, critical}
     * @param openIssues       issues not closed
     * @param lastTestRun      most recent system test run, or null
     * @param recentCritical   latest critical alerts
     * @param recentAudit      latest audit log entries
     */
    public record Overview(String softwareVersion, int updatesAvailable, int users, int lockedUsers,
                           TripRepository.Totals today, TripRepository.Totals allTime, int[] alerts24h, int openIssues,
                           MaintenanceRepository.TestRun lastTestRun, List<AlertRepository.Stored> recentCritical,
                           List<AuditRepository.Entry> recentAudit) {
    }

    private final UserRepository users;
    private final TripRepository trips;
    private final AlertRepository alerts;
    private final AuditRepository audit;
    private final IssueRepository issues;
    private final MaintenanceRepository maintenance;
    private final UpdateRepository updates;
    private final SettingsService settings;
    private final Clock clock;

    public DashboardService(UserRepository users, TripRepository trips, AlertRepository alerts, AuditRepository audit,
                            IssueRepository issues, MaintenanceRepository maintenance, UpdateRepository updates,
                            SettingsService settings, Clock clock) {
        this.users = users;
        this.trips = trips;
        this.alerts = alerts;
        this.audit = audit;
        this.issues = issues;
        this.maintenance = maintenance;
        this.updates = updates;
        this.settings = settings;
        this.clock = clock;
    }

    public Overview overview(Session session) {
        session.require(Permission.VIEW_PERFORMANCE);
        Instant now = clock.instant();
        Instant midnight = now.atZone(ZoneId.systemDefault()).toLocalDate().atStartOfDay(ZoneId.systemDefault())
                .toInstant();
        List<User> all = users.all();
        int locked = (int) all.stream().filter(u -> u.lockedUntil() != null && u.lockedUntil().isAfter(now)).count();
        String version = settings.get(SettingsService.SOFTWARE_VERSION);
        int available = (int) updates.all().stream()
                .filter(u -> u.status() == UpdateRepository.Status.AVAILABLE
                        && UpdateService.compare(u.version(), version) > 0).count();
        List<MaintenanceRepository.TestRun> runs = maintenance.runs(1);
        return new Overview(version, available, all.size(), locked, trips.totals(midnight),
                trips.totals(Instant.EPOCH), alerts.countsSince(now.minus(Duration.ofHours(24))),
                issues.countUnclosed(), runs.isEmpty() ? null : runs.get(0),
                alerts.recent(5, Alert.Severity.CRITICAL, null), audit.recent(8));
    }
}
