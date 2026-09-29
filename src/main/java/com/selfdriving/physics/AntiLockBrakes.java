package com.selfdriving.physics;

/**
 * Anti-lock braking for one wheel.
 *
 * <p>Works like a real ABS valve: if the wheel slows down much faster than the car (slip ratio
 * well past the tyre's peak), brake pressure is released; once the wheel has spun back up,
 * pressure is re-applied. The wheel keeps cycling around peak grip, so the car stops about as
 * fast as the road allows and can still be steered.
 */
public final class AntiLockBrakes {

    /** Below this speed ABS stays out of the way (a wheel may lock at walking pace). */
    public static final double MIN_ACTIVE_SPEED = 1.5;

    private static final double RELEASE_FACTOR = 1.3;
    private static final double REAPPLY_FACTOR = 0.8;
    private static final double RELEASE_RATE = 25.0;
    private static final double REAPPLY_RATE = 10.0;
    private static final double MIN_PRESSURE = 0.05;

    private double pressure = 1.0;
    private boolean active;

    /**
     * Updates the valve and returns the pressure factor to apply to the driver's brake demand.
     *
     * @param enabled    whether ABS is switched on
     * @param brakeInput driver's brake demand, 0..1
     * @param slipRatio  current wheel slip ratio (negative while braking)
     * @param speed      wheel speed over ground, m/s
     * @param surface    road surface (sets the target slip)
     * @param dt         time step, s
     */
    public double update(boolean enabled, double brakeInput, double slipRatio, double speed,
                         Surface surface, double dt) {
        if (!enabled || brakeInput <= 0.01 || Math.abs(speed) < MIN_ACTIVE_SPEED) {
            pressure = 1.0;
            active = false;
            return pressure;
        }
        double peak = surface.peakSlip();
        if (slipRatio < -RELEASE_FACTOR * peak) {
            pressure = Math.max(MIN_PRESSURE, pressure - RELEASE_RATE * dt);
            active = true;
        } else if (slipRatio > -REAPPLY_FACTOR * peak) {
            pressure = Math.min(1.0, pressure + REAPPLY_RATE * dt);
            if (pressure >= 1.0) {
                active = false;
            }
        }
        return pressure;
    }

    /** True while ABS is modulating this wheel. */
    public boolean isActive() {
        return active;
    }

    public void reset() {
        pressure = 1.0;
        active = false;
    }
}
