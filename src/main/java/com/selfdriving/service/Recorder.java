package com.selfdriving.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.selfdriving.alerts.Alert;
import com.selfdriving.persistence.AlertRepository;
import com.selfdriving.persistence.TripRepository;
import com.selfdriving.simulation.SimulationListener;

/**
 * Saves what happens on the road: every trip (with who was driving) and every alert (with who
 * acknowledged it). Listens to the simulation and writes on the {@link DbWriter} thread.
 *
 * <p>The id maps below are touched only on the writer thread.
 */
public final class Recorder implements SimulationListener {

    /** Alerts remembered for acknowledgement (older ones can no longer be acknowledged on screen). */
    private static final int ALERT_IDS_KEPT = 500;

    private final TripRepository trips;
    private final AlertRepository alerts;
    private final DbWriter writer;
    private final Clock clock;
    private final Supplier<Session> session;
    private final Map<Long, Long> tripIds = new HashMap<>();
    private final Map<Long, Long> alertIds = new LinkedHashMap<>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Long> eldest) {
            return size() > ALERT_IDS_KEPT;
        }
    };
    private final CopyOnWriteArrayList<Runnable> tripListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> alertListeners = new CopyOnWriteArrayList<>();

    /**
     * @param session who is logged in right now (null when nobody is)
     */
    public Recorder(TripRepository trips, AlertRepository alerts, DbWriter writer, Clock clock,
                    Supplier<Session> session) {
        this.trips = trips;
        this.alerts = alerts;
        this.writer = writer;
        this.clock = clock;
        this.session = session;
    }

    /** Called on the writer thread after a trip is saved or updated. */
    public void onTripSaved(Runnable listener) {
        tripListeners.add(listener);
    }

    /** Called on the writer thread after an alert is saved or acknowledged. */
    public void onAlertSaved(Runnable listener) {
        alertListeners.add(listener);
    }

    @Override
    public void tripStarted(long tripId, String origin, String destination, double plannedM, double etaS) {
        Long driver = currentUserId();
        var at = clock.instant();
        writer.run("trip", () -> {
            tripIds.put(tripId, trips.start(driver, origin, destination, plannedM, etaS, at));
            tripListeners.forEach(Runnable::run);
        });
    }

    @Override
    public void tripEnded(TripSummary s) {
        var at = clock.instant();
        writer.run("trip", () -> {
            Long id = tripIds.remove(s.tripId());
            if (id != null) {
                trips.finish(id, s.drivenMetres(), s.durationSeconds(), s.energyKwh(), s.autopilotShare(),
                        s.outcome().name(), at);
                tripListeners.forEach(Runnable::run);
            }
        });
    }

    @Override
    public void alertRaised(Alert alert) {
        var at = clock.instant();
        writer.run("alert", () -> {
            alertIds.put(alert.id(), alerts.insert(alert, at));
            alertListeners.forEach(Runnable::run);
        });
    }

    @Override
    public void alertAcknowledged(long alertId) {
        Long user = currentUserId();
        var at = clock.instant();
        writer.run("alert acknowledgement", () -> {
            Long id = alertIds.get(alertId);
            if (id != null) {
                alerts.acknowledge(id, user, at);
                alertListeners.forEach(Runnable::run);
            }
        });
    }

    private Long currentUserId() {
        Session s = session.get();
        return s == null ? null : s.userId();
    }
}
