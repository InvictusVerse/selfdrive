package com.selfdriving.vehicle;

import com.selfdriving.physics.VehicleParams;

/**
 * Turns on/off keys into smooth pedal and steering positions, like a real foot and hands would:
 * pedals ramp up and down, and the steering wheel turns at a limited rate and centres itself.
 *
 * <p>Steering is also speed-sensitive: at speed a full key press asks for less wheel angle, so
 * the car stays controllable on a keyboard. The limit is set a little above what dry tyres can
 * deliver, so the car can still be pushed into a slide, and easily on wet or icy roads.
 */
public final class DriverControls {

    private static final double THROTTLE_RISE = 2.0;
    private static final double THROTTLE_FALL = 5.0;
    private static final double BRAKE_RISE = 1.6;
    private static final double FULL_BRAKE_RISE = 12.0;
    private static final double BRAKE_FALL = 6.0;
    private static final double STEER_RATE = 1.8;
    private static final double STEER_REVERSE_RATE = 3.5;
    private static final double STEER_CENTRE_RATE = 2.5;

    /** Lateral acceleration the speed-sensitive steering is sized for, m/s^2. */
    private static final double STEER_LATERAL_LIMIT = 12.0;

    private final VehicleParams params;
    private double throttle;
    private double brake;
    private double steer;

    public DriverControls(VehicleParams params) {
        this.params = params;
    }

    /**
     * Advances the smoothed controls.
     *
     * @param input key state
     * @param speed current speed over ground, m/s
     * @param dt    time step, s
     */
    public void update(DriverInput input, double speed, double dt) {
        throttle = approach(throttle, input.accelerate() ? 1 : 0, THROTTLE_RISE, THROTTLE_FALL, dt);

        double brakeTarget = input.fullBrake() || input.brake() ? 1 : 0;
        double brakeRise = input.fullBrake() ? FULL_BRAKE_RISE : BRAKE_RISE;
        brake = approach(brake, brakeTarget, brakeRise, BRAKE_FALL, dt);

        double steerTarget = (input.steerLeft() ? 1 : 0) - (input.steerRight() ? 1 : 0);
        if (steerTarget == 0) {
            steer = moveTowards(steer, 0, STEER_CENTRE_RATE * dt);
        } else {
            boolean reversing = Math.signum(steer) != 0 && Math.signum(steer) != steerTarget;
            steer = moveTowards(steer, steerTarget, (reversing ? STEER_REVERSE_RATE : STEER_RATE) * dt);
        }
    }

    /** Accelerator position, 0..1. */
    public double throttle() {
        return throttle;
    }

    /** Brake pedal position, 0..1. */
    public double brake() {
        return brake;
    }

    /** Steering wheel position, -1 (full right) .. +1 (full left). */
    public double steer() {
        return steer;
    }

    /** Road-wheel angle for the current steering position and speed, rad. */
    public double steerAngle(double speed) {
        return steer * maxSteerAngle(speed);
    }

    /** Largest wheel angle a full steering input gives at this speed, rad. */
    public double maxSteerAngle(double speed) {
        double v2 = speed * speed;
        if (v2 < 1e-6) {
            return params.maxSteerAngle();
        }
        return Math.min(params.maxSteerAngle(), Math.atan(params.wheelbase() * STEER_LATERAL_LIMIT / v2));
    }

    public void reset() {
        throttle = brake = steer = 0;
    }

    private static double approach(double value, double target, double rise, double fall, double dt) {
        return target > value
                ? Math.min(target, value + rise * dt)
                : Math.max(target, value - fall * dt);
    }

    private static double moveTowards(double value, double target, double maxStep) {
        double delta = target - value;
        return Math.abs(delta) <= maxStep ? target : value + Math.signum(delta) * maxStep;
    }
}
