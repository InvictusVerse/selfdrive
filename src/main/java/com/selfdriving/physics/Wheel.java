package com.selfdriving.physics;

/**
 * State of one wheel. Updated by {@link VehicleModel}; read-only for everyone else.
 */
public final class Wheel {

    double omega;
    double rotation;
    double steerAngle;
    double load;
    double slipRatio;
    double slipAngle;
    double forceLongitudinal;
    double forceLateral;
    double forceCarX;
    double forceCarY;
    double gripUsage;
    double driveTorque;
    double brakeTorque;
    boolean absActive;

    void reset() {
        omega = rotation = steerAngle = slipRatio = slipAngle = 0;
        forceLongitudinal = forceLateral = forceCarX = forceCarY = gripUsage = 0;
        driveTorque = brakeTorque = 0;
        absActive = false;
    }

    /** Angular velocity, rad/s (positive = rolling forward). */
    public double omega() {
        return omega;
    }

    /** Accumulated rotation angle for drawing, rad (wrapped to 0..2 pi). */
    public double rotation() {
        return rotation;
    }

    /** Road-wheel steering angle, rad (positive = left). */
    public double steerAngle() {
        return steerAngle;
    }

    /** Vertical load, N. */
    public double load() {
        return load;
    }

    /** Longitudinal slip ratio (negative braking, positive spinning). */
    public double slipRatio() {
        return slipRatio;
    }

    /** Slip angle, rad. */
    public double slipAngle() {
        return slipAngle;
    }

    /** Tyre force along the wheel, N. */
    public double forceLongitudinal() {
        return forceLongitudinal;
    }

    /** Tyre force across the wheel, N. */
    public double forceLateral() {
        return forceLateral;
    }

    /** Tyre force in the car's frame, forward component, N. */
    public double forceCarX() {
        return forceCarX;
    }

    /** Tyre force in the car's frame, leftward component, N. */
    public double forceCarY() {
        return forceCarY;
    }

    /** Share of available grip in use: |force| / (mu * load). 1.0 = at the limit. */
    public double gripUsage() {
        return gripUsage;
    }

    /** Motor torque delivered to this wheel, N*m. */
    public double driveTorque() {
        return driveTorque;
    }

    /** Brake torque applied, N*m. */
    public double brakeTorque() {
        return brakeTorque;
    }

    /** True while ABS is modulating this wheel. */
    public boolean absActive() {
        return absActive;
    }
}
