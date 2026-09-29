package com.selfdriving.physics;

/**
 * Electric motor with a fixed reduction gear.
 *
 * <p>Torque curve: constant torque up to the base speed, then constant power
 * ({@code T = P / omega}), fading to zero just below the rpm limit. With the accelerator released
 * in Drive the motor works as a generator (regenerative braking). Regen fades out at walking pace
 * so the car rolls to a gentle stop instead of reversing.
 */
public final class ElectricMotor {

    private static final double RAD_PER_SEC_TO_RPM = 60.0 / (2 * Math.PI);

    private final VehicleParams.MotorParams params;

    public ElectricMotor(VehicleParams.MotorParams params) {
        this.params = params;
    }

    /**
     * Motor torque for this instant.
     *
     * @param gear          drive selector position
     * @param throttle      accelerator pedal, 0..1
     * @param motorOmega    motor shaft speed, rad/s (signed, positive = forward)
     * @param vehicleSpeed  longitudinal speed of the car, m/s (signed)
     * @return motor torque, N*m (positive pushes the car forward)
     */
    public double torque(Gear gear, double throttle, double motorOmega, double vehicleSpeed) {
        return switch (gear) {
            case DRIVE -> throttle > 0
                    ? throttle * available(motorOmega)
                    : vehicleSpeed > 0 ? -regen(motorOmega, vehicleSpeed) : 0;
            case REVERSE -> {
                if (throttle > 0) {
                    double reverseSpeed = Math.max(0, -vehicleSpeed);
                    double limit = clamp(params.reverseMaxSpeed() - reverseSpeed, 0, 1);
                    yield -throttle * available(motorOmega) * limit;
                }
                yield vehicleSpeed < 0 ? regen(motorOmega, vehicleSpeed) : 0;
            }
            case NEUTRAL, PARK -> 0;
        };
    }

    /**
     * Deceleration full regeneration would give at a speed, m/s^2 (for controllers that want
     * to blend regen and friction brakes).
     *
     * @param vehicleSpeed m/s
     * @param wheelRadius  m
     * @param mass         kg
     */
    public double regenDeceleration(double vehicleSpeed, double wheelRadius, double mass) {
        double motorOmega = Math.abs(vehicleSpeed) / wheelRadius * params.gearRatio();
        return regen(motorOmega, vehicleSpeed) * params.gearRatio() / wheelRadius / mass;
    }

    /** Largest drive force at a speed, N. */
    public double driveForce(double vehicleSpeed, double wheelRadius) {
        double motorOmega = Math.abs(vehicleSpeed) / wheelRadius * params.gearRatio();
        return available(motorOmega) * params.gearRatio() / wheelRadius;
    }

    /** Maximum drive torque available at this motor speed, N*m. */
    public double available(double motorOmega) {
        double speed = Math.abs(motorOmega);
        double rpm = speed * RAD_PER_SEC_TO_RPM;
        double rpmFade = clamp((params.maxRpm() - rpm) / (0.05 * params.maxRpm()), 0, 1);
        double powerLimited = params.maxPower() / Math.max(speed, 1.0);
        return Math.min(params.maxTorque(), powerLimited) * rpmFade;
    }

    /** Motor speed in rpm for a shaft speed in rad/s. */
    public static double toRpm(double motorOmega) {
        return Math.abs(motorOmega) * RAD_PER_SEC_TO_RPM;
    }

    public double gearRatio() {
        return params.gearRatio();
    }

    public double frontTorqueShare() {
        return params.frontTorqueShare();
    }

    /**
     * Electrical power drawn from (positive) or returned to (negative) the battery for a given
     * mechanical power at the motor shaft.
     */
    public double electricalPower(double mechanicalPower) {
        return mechanicalPower >= 0
                ? mechanicalPower / params.driveEfficiency()
                : mechanicalPower * params.regenEfficiency();
    }

    private double regen(double motorOmega, double vehicleSpeed) {
        double speed = Math.abs(motorOmega);
        double torque = Math.min(params.regenMaxTorque(), params.regenMaxPower() / Math.max(speed, 1.0));
        double lowSpeedFade = clamp((Math.abs(vehicleSpeed) - 0.5) / 1.5, 0, 1);
        return torque * lowSpeedFade;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
