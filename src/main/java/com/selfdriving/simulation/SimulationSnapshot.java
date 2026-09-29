package com.selfdriving.simulation;

import java.util.List;
import java.util.Set;

import com.selfdriving.alerts.Alert;
import com.selfdriving.autopilot.SafetyController;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.navigation.Route;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.vehicle.LightState;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;

/**
 * Everything the display needs after one simulation tick. Immutable.
 *
 * @param tick               tick counter
 * @param time               simulated time, s
 * @param vehicle            car state
 * @param mode               who is driving
 * @param navigation         route progress, or null without a route
 * @param autopilot          autopilot status, or null when not engaged
 * @param safety             collision warning / emergency braking assessment
 * @param sensors            latest sensor scan
 * @param actors             moving and temporary objects (pedestrians, vehicles, barriers)
 * @param closedEdges        ids of closed road edges
 * @param alerts             recent alerts, newest first
 * @param criticalAlerts     critical alerts not yet acknowledged
 * @param settings           driver-adjustable settings
 * @param lastBrakeTest      most recent braking test, or null
 * @param lastAccelTest      most recent 0-100 km/h run, or null
 * @param accelTestRunning   true while a 0-100 km/h run is being timed
 * @param accelTestTime      elapsed time of the running 0-100 km/h run, s
 * @param brakeTestRunning   true while a braking test is being measured
 * @param paused             simulation paused
 * @param timeScale          simulation speed multiplier (1 = real time)
 * @param lights             exterior lights
 * @param timeOfDay          local clock, seconds since midnight
 * @param outsideTemperature air temperature, degrees Celsius
 * @param faults             faults present in the car
 * @param softwareUpdating   a software update is being installed (the car stays in Park)
 */
public record SimulationSnapshot(
        long tick,
        double time,
        VehicleState vehicle,
        DriveMode mode,
        Navigation navigation,
        AutopilotStatus autopilot,
        SafetyController.Assessment safety,
        SensorReadings sensors,
        List<ActorState> actors,
        Set<Integer> closedEdges,
        List<Alert> alerts,
        List<Alert> criticalAlerts,
        Settings settings,
        PerformanceMonitor.BrakeTest lastBrakeTest,
        PerformanceMonitor.AccelerationTest lastAccelTest,
        boolean accelTestRunning,
        double accelTestTime,
        boolean brakeTestRunning,
        boolean paused,
        double timeScale,
        LightState lights,
        double timeOfDay,
        double outsideTemperature,
        Set<Fault> faults,
        boolean softwareUpdating) {

    public SimulationSnapshot {
        faults = faults.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(faults));
        actors = List.copyOf(actors);
        closedEdges = Set.copyOf(closedEdges);
        alerts = List.copyOf(alerts);
        criticalAlerts = List.copyOf(criticalAlerts);
    }

    /**
     * Route progress.
     *
     * @param route               the planned route (immutable)
     * @param arc                 distance driven along it, m
     * @param remainingDistance   m
     * @param remainingSeconds    s
     * @param nextInstruction     next direction
     * @param distanceToNext      distance to the next direction, m
     * @param speedLimit          current road's speed limit, m/s
     * @param lateralError        distance from the planned path, m
     */
    public record Navigation(Route route, double arc, double remainingDistance, double remainingSeconds,
                             String nextInstruction, double distanceToNext, double speedLimit,
                             double lateralError) {
    }

    /**
     * What the autopilot is doing.
     *
     * @param status       short explanation for the driver
     * @param targetSpeed  speed it is aiming for, m/s
     * @param leadObjectId object it is following or stopping for, or -1
     * @param signal       traffic light at the next junction on the route (NONE if none)
     * @param signalDistance distance to that light's stop line, m
     */
    public record AutopilotStatus(String status, double targetSpeed, int leadObjectId,
                                  com.selfdriving.world.RoadNetwork.Signal signal, double signalDistance) {
    }

    /**
     * A moving or temporary object.
     *
     * @param id        obstacle id
     * @param kind      what it is
     * @param box       footprint
     * @param height    m
     * @param detected  whether the car's sensors currently see it
     * @param braking   brake lights on (traffic)
     * @param indicator 1 = left, -1 = right, 0 = none (traffic)
     * @param hazard    hazard lights on (traffic)
     */
    public record ActorState(int id, Obstacle.Kind kind, OrientedBox box, double height, boolean detected,
                             boolean braking, int indicator, boolean hazard) {

        public ActorState(int id, Obstacle.Kind kind, OrientedBox box, double height, boolean detected) {
            this(id, kind, box, height, detected, false, 0, false);
        }
    }

    /**
     * Driver-adjustable settings.
     *
     * @param maxAutopilotSpeed      m/s
     * @param emergencyBrakingEnabled automatic emergency braking on
     * @param trafficCount           other vehicles kept on the roads
     */
    public record Settings(double maxAutopilotSpeed, boolean emergencyBrakingEnabled, int trafficCount) {
    }
}
