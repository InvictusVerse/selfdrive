package com.selfdriving.physics;

/**
 * Traction control: cuts motor torque when a driven wheel spins faster than the tyre can use.
 * Torque is restored gradually once the wheels grip again.
 */
public final class TractionControl {

    private static final double SPIN_FACTOR = 1.3;
    private static final double CUT_RATE = 8.0;
    private static final double RESTORE_RATE = 3.0;
    private static final double MIN_TORQUE = 0.1;

    private double torqueFactor = 1.0;
    private boolean active;

    /**
     * @param enabled      whether traction control is switched on
     * @param maxSlipRatio largest slip ratio of the driven wheels in the drive direction
     * @param surface      road surface (sets the allowed slip)
     * @param dt           time step, s
     * @return factor to multiply motor torque by, 0..1
     */
    public double update(boolean enabled, double maxSlipRatio, Surface surface, double dt) {
        if (!enabled) {
            torqueFactor = 1.0;
            active = false;
            return torqueFactor;
        }
        if (maxSlipRatio > SPIN_FACTOR * surface.peakSlip()) {
            torqueFactor = Math.max(MIN_TORQUE, torqueFactor - CUT_RATE * dt);
            active = true;
        } else {
            torqueFactor = Math.min(1.0, torqueFactor + RESTORE_RATE * dt);
            if (torqueFactor >= 1.0) {
                active = false;
            }
        }
        return torqueFactor;
    }

    /** True while torque is being reduced. */
    public boolean isActive() {
        return active;
    }

    public void reset() {
        torqueFactor = 1.0;
        active = false;
    }
}
