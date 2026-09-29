package com.selfdriving.simulation;

import com.selfdriving.vehicle.VehicleState;

/**
 * Everything the display needs after one simulation tick.
 *
 * @param tick               tick counter
 * @param time               simulated time, s
 * @param vehicle            car state
 * @param lastBrakeTest      most recent braking test, or null
 * @param lastAccelTest      most recent 0-100 km/h run, or null
 * @param accelTestRunning   true while a 0-100 km/h run is being timed
 * @param accelTestTime      elapsed time of the running 0-100 km/h run, s
 * @param brakeTestRunning   true while a braking test is being measured
 * @param paused             simulation paused
 * @param timeScale          simulation speed multiplier (1 = real time)
 */
public record SimulationSnapshot(
        long tick,
        double time,
        VehicleState vehicle,
        PerformanceMonitor.BrakeTest lastBrakeTest,
        PerformanceMonitor.AccelerationTest lastAccelTest,
        boolean accelTestRunning,
        double accelTestTime,
        boolean brakeTestRunning,
        boolean paused,
        double timeScale) {
}
