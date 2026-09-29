package com.selfdriving.autopilot;

import com.selfdriving.physics.VehicleModel;
import com.selfdriving.physics.VehicleParams;

/**
 * Longitudinal control: turns a target speed into accelerator, regeneration and brake.
 *
 * <ol>
 *   <li>A PI controller turns the speed error into a wanted acceleration.</li>
 *   <li>Air drag and rolling resistance are added back (feed-forward), because they slow the
 *       car whether we like it or not.</li>
 *   <li>Positive demand becomes accelerator, sized by the force the motor can give at this speed.</li>
 *   <li>Deceleration is done with regeneration first (recovering energy) and the friction brakes
 *       only for what regen cannot deliver.</li>
 * </ol>
 */
public final class SpeedController {

    /** Output pedals. */
    public record Pedals(double throttle, double brake, double regen) {
    }

    /** Hardest deceleration the autopilot asks for in normal driving, m/s^2 (emergencies use the safety controller). */
    public static final double MAX_DECEL = 6.0;
    private static final double MAX_ACCEL = 2.5;
    private static final double BRAKE_DECEL_PER_UNIT = 12.0;
    private static final double HOLD_BRAKE = 0.35;

    private final VehicleParams params;
    private final PidController pid = new PidController(1.0, 0.25, 0.0, 3.0);

    public SpeedController(VehicleParams params) {
        this.params = params;
    }

    /** Pedals for a target speed, with no extra limit on acceleration. */
    public Pedals command(double targetSpeed, VehicleModel car, double dt) {
        return command(targetSpeed, Double.POSITIVE_INFINITY, car, dt);
    }

    /**
     * Pedals for a target speed.
     *
     * @param accelLimit highest acceleration allowed, m/s^2. A negative value is a deceleration
     *                   the planner already knows is needed (e.g. to stop behind a car); it acts as
     *                   feed-forward, so the car brakes early and smoothly instead of lagging the plan.
     */
    public Pedals command(double targetSpeed, double accelLimit, VehicleModel car, double dt) {
        double v = car.forwardSpeed();
        if (targetSpeed < 0.2 && Math.abs(v) < 0.3) {
            pid.reset();
            return new Pedals(0, HOLD_BRAKE, 1); // hold the car at standstill
        }
        double feedback = pid.update(targetSpeed - v, dt);
        double wanted = Math.max(-MAX_DECEL, Math.min(MAX_ACCEL, Math.min(feedback, accelLimit)));

        double m = params.mass();
        double resistance = (0.5 * VehicleParams.AIR_DENSITY * params.dragCoefficient() * params.frontalArea() * v * v
                + params.rollingResistance() * m * VehicleParams.GRAVITY) / m;
        double net = wanted + resistance;
        if (net >= 0) {
            double force = car.motor().driveForce(v, params.wheelRadius());
            return new Pedals(Math.min(1, net * m / Math.max(1, force)), 0, 0);
        }
        double decel = -net;
        double regenMax = car.motor().regenDeceleration(v, params.wheelRadius(), m);
        double regen = regenMax > 0.05 ? Math.min(1, decel / regenMax) : 0;
        double remaining = decel - regen * regenMax;
        double brake = remaining > 0 ? Math.min(1, remaining / BRAKE_DECEL_PER_UNIT) : 0;
        return new Pedals(0, brake, regen);
    }

    public void reset() {
        pid.reset();
    }
}
