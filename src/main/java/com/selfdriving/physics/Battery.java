package com.selfdriving.physics;

/** Traction battery: tracks stored energy as power flows in and out. */
public final class Battery {

    private static final double JOULES_PER_KWH = 3.6e6;

    private final double capacityJoules;
    private double storedJoules;

    /**
     * @param capacityKwh   usable capacity, kWh
     * @param stateOfCharge initial charge, 0..1
     */
    public Battery(double capacityKwh, double stateOfCharge) {
        this.capacityJoules = capacityKwh * JOULES_PER_KWH;
        this.storedJoules = capacityJoules * clamp(stateOfCharge);
    }

    /**
     * Draws power for a time step. Positive power discharges, negative power (regen) charges.
     *
     * @return the energy actually taken from the battery, J (negative when charging)
     */
    public double apply(double powerWatts, double dt) {
        double before = storedJoules;
        storedJoules = Math.max(0, Math.min(capacityJoules, storedJoules - powerWatts * dt));
        return before - storedJoules;
    }

    /** State of charge, 0..1. */
    public double stateOfCharge() {
        return storedJoules / capacityJoules;
    }

    /** Energy left, kWh. */
    public double remainingKwh() {
        return storedJoules / JOULES_PER_KWH;
    }

    public boolean isEmpty() {
        return storedJoules <= 0;
    }

    public void setStateOfCharge(double stateOfCharge) {
        storedJoules = capacityJoules * clamp(stateOfCharge);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
