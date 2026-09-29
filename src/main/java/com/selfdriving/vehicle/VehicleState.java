package com.selfdriving.vehicle;

import java.util.List;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;

/**
 * Immutable snapshot of the car at one moment: the single source of truth that the display,
 * 3D view, diagnostics and database read. A new snapshot is published after every simulation
 * tick, so readers on other threads never see a half-updated car.
 *
 * @param x                  world position east, m
 * @param y                  world position north, m
 * @param heading            heading, rad, counter-clockwise from east
 * @param forwardSpeed       speed along the heading, m/s (negative in reverse)
 * @param lateralSpeed       sideways speed, m/s (positive = left)
 * @param yawRate            rad/s (positive = turning left)
 * @param accelForward       m/s^2 (negative = braking)
 * @param accelLeft          m/s^2
 * @param sideslipAngle      angle between heading and direction of travel, rad
 * @param steerInput         steering wheel position, -1..1
 * @param steerAngle         road-wheel angle, rad
 * @param throttle           accelerator, 0..1
 * @param brake              brake pedal, 0..1
 * @param gear               drive selector
 * @param surface            road surface
 * @param absEnabled         ABS switched on
 * @param tractionEnabled    traction control switched on
 * @param absActive          ABS modulating at least one wheel
 * @param tractionActive     traction control reducing torque
 * @param pitch              body pitch, rad (positive = nose down)
 * @param roll               body roll, rad (positive = right side down)
 * @param heave              body heave, m (positive = lower)
 * @param motorTorque        N*m
 * @param motorRpm           rpm
 * @param motorPowerKw       mechanical power at the motor, kW (negative = regenerating)
 * @param batteryPowerKw     electrical power from the battery, kW (negative = charging)
 * @param batteryCharge      state of charge, 0..1
 * @param batteryKwh         energy left, kWh
 * @param tripDistance       distance since start, m
 * @param tripEnergyKwh      net energy used since start, kWh
 * @param consumptionWhPerKm average consumption, Wh/km
 * @param rangeKm            estimated remaining range, km
 * @param wheels             front-left, front-right, rear-left, rear-right
 */
public record VehicleState(
        double x,
        double y,
        double heading,
        double forwardSpeed,
        double lateralSpeed,
        double yawRate,
        double accelForward,
        double accelLeft,
        double sideslipAngle,
        double steerInput,
        double steerAngle,
        double throttle,
        double brake,
        Gear gear,
        Surface surface,
        boolean absEnabled,
        boolean tractionEnabled,
        boolean absActive,
        boolean tractionActive,
        double pitch,
        double roll,
        double heave,
        double motorTorque,
        double motorRpm,
        double motorPowerKw,
        double batteryPowerKw,
        double batteryCharge,
        double batteryKwh,
        double tripDistance,
        double tripEnergyKwh,
        double consumptionWhPerKm,
        double rangeKm,
        List<WheelState> wheels) {

    public VehicleState {
        wheels = List.copyOf(wheels);
    }

    /** Speed over ground, m/s. */
    public double speed() {
        return Math.hypot(forwardSpeed, lateralSpeed);
    }

    /** Speed over ground, km/h. */
    public double speedKmh() {
        return speed() * 3.6;
    }

    /**
     * One wheel.
     *
     * @param steerAngle   rad
     * @param rotation     rad, for drawing the spinning wheel
     * @param omega        rad/s
     * @param load         vertical load, N
     * @param slipRatio    longitudinal slip ratio
     * @param slipAngle    rad
     * @param forceX       tyre force forward in the car frame, N
     * @param forceY       tyre force left in the car frame, N
     * @param gripUsage    share of available grip in use (1.0 = at the limit)
     * @param absActive    ABS modulating this wheel
     * @param compression  suspension compression, m
     */
    public record WheelState(
            double steerAngle,
            double rotation,
            double omega,
            double load,
            double slipRatio,
            double slipAngle,
            double forceX,
            double forceY,
            double gripUsage,
            boolean absActive,
            double compression) {
    }
}
