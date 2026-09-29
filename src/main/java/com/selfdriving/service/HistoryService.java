package com.selfdriving.service;

import java.util.List;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.Permission;
import com.selfdriving.persistence.AlertRepository;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.persistence.TripRepository;

/** Reading back trips, alerts and the audit log, limited to what the user may see. */
public final class HistoryService {

    private final TripRepository trips;
    private final AlertRepository alerts;
    private final AuditRepository audit;

    public HistoryService(TripRepository trips, AlertRepository alerts, AuditRepository audit) {
        this.trips = trips;
        this.alerts = alerts;
        this.audit = audit;
    }

    /** Everyone's trips for users who may see them, otherwise only the user's own. */
    public List<TripRepository.Trip> trips(Session session, int limit) {
        if (session.can(Permission.VIEW_ALL_TRIPS)) {
            return trips.all(limit);
        }
        session.require(Permission.VIEW_OWN_TRIPS);
        return trips.forDriver(session.userId(), limit);
    }

    public List<AlertRepository.Stored> alerts(Session session, int limit, Alert.Severity minSeverity,
                                               Alert.Category category) {
        session.require(Permission.VIEW_ALERTS);
        return alerts.recent(limit, minSeverity, category);
    }

    public List<AuditRepository.Entry> audit(Session session, int limit) {
        session.require(Permission.VIEW_AUDIT_LOG);
        return audit.recent(limit);
    }
}
