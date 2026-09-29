package com.selfdriving.simulation;

import com.selfdriving.alerts.Alert;

/**
 * Events from the simulation for the rest of the system (saving trips and alerts).
 *
 * <p>Called on the simulation thread: implementations must return quickly and hand any slow
 * work (database writes) to another thread.
 */
public interface SimulationListener {

    /** How a trip ended. */
    enum Outcome { ARRIVED, CANCELLED }

    /**
     * A finished trip.
     *
     * @param tripId          the id given in {@link #tripStarted}
     * @param drivenMetres    distance actually driven, m
     * @param durationSeconds simulated time from start to end, s
     * @param energyKwh       battery energy used (net of regeneration), kWh
     * @param autopilotShare  fraction of the time the autopilot drove, 0-1
     * @param outcome         arrived or cancelled
     */
    record TripSummary(long tripId, double drivenMetres, double durationSeconds, double energyKwh,
                       double autopilotShare, Outcome outcome) {
    }

    /**
     * A route was planned and the trip begins.
     *
     * @param tripId      unique within this run of the simulation
     * @param origin      where the car is
     * @param destination where it is going
     * @param plannedM    route length, m
     * @param etaS        estimated driving time, s
     */
    default void tripStarted(long tripId, String origin, String destination, double plannedM, double etaS) {
    }

    default void tripEnded(TripSummary summary) {
    }

    default void alertRaised(Alert alert) {
    }

    default void alertAcknowledged(long alertId) {
    }
}
