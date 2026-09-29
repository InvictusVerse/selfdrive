package com.selfdriving.physics;

/**
 * Suspension and weight transfer.
 *
 * <p>The car body is a sprung mass with three degrees of freedom: heave (up/down),
 * pitch (nose up/down) and roll (lean). Each corner has a spring and a damper
 * ({@code F = k*x + c*v}); each axle also has an anti-roll bar. Braking and cornering push the
 * body through its centre of gravity, which sits above the road, so the body pitches and rolls
 * and load moves between the tyres. Tyre grip depends on load, so this is what makes the car
 * understeer, oversteer or lift a wheel.
 *
 * <p>Sign convention: pitch positive = nose down, roll positive = right side down,
 * compression positive. Wheel order: front-left, front-right, rear-left, rear-right.
 */
public final class Suspension {

    private final VehicleParams params;
    private final double[] wheelX = new double[4];
    private final double[] wheelY = new double[4];
    private final double[] staticLoad = new double[4];
    private final double[] load = new double[4];
    private final double[] compression = new double[4];

    private double heave;
    private double heaveRate;
    private double pitch;
    private double pitchRate;
    private double roll;
    private double rollRate;

    public Suspension(VehicleParams params) {
        this.params = params;
        double a = params.cgToFrontAxle();
        double b = params.cgToRearAxle();
        double halfTrack = params.trackWidth() / 2;
        double weight = params.mass() * VehicleParams.GRAVITY;
        double frontAxleLoad = weight * b / params.wheelbase();
        double rearAxleLoad = weight * a / params.wheelbase();

        setWheel(VehicleModel.FL, a, halfTrack, frontAxleLoad / 2);
        setWheel(VehicleModel.FR, a, -halfTrack, frontAxleLoad / 2);
        setWheel(VehicleModel.RL, -b, halfTrack, rearAxleLoad / 2);
        setWheel(VehicleModel.RR, -b, -halfTrack, rearAxleLoad / 2);
        reset();
    }

    private void setWheel(int index, double x, double y, double staticN) {
        wheelX[index] = x;
        wheelY[index] = y;
        staticLoad[index] = staticN;
    }

    /** Returns the body to rest with static loads. */
    public void reset() {
        heave = heaveRate = pitch = pitchRate = roll = rollRate = 0;
        System.arraycopy(staticLoad, 0, load, 0, 4);
        java.util.Arrays.fill(compression, 0);
    }

    /**
     * Advances the body motion.
     *
     * @param dt           time step, s
     * @param accelForward body acceleration forward (sum of horizontal forces / mass), m/s^2
     * @param accelLeft    body acceleration to the left, m/s^2
     */
    public void step(double dt, double accelForward, double accelLeft) {
        double k = params.springRate();
        double c = params.damperRate();
        double halfTrack = params.trackWidth() / 2;

        double heaveForce = 0;
        double pitchMoment = 0;
        double rollMoment = 0;
        for (int i = 0; i < 4; i++) {
            double s = heave + wheelX[i] * pitch - wheelY[i] * roll;
            double sRate = heaveRate + wheelX[i] * pitchRate - wheelY[i] * rollRate;
            double antiRoll = antiRollForce(i, halfTrack);
            double dynamic = k * s + c * sRate + antiRoll;

            compression[i] = s;
            load[i] = Math.max(0, staticLoad[i] + dynamic);

            heaveForce -= dynamic;
            pitchMoment -= wheelX[i] * dynamic;
            rollMoment += wheelY[i] * dynamic;
        }

        double m = params.mass();
        double h = params.cgHeight();
        pitchMoment += -m * accelForward * h;
        rollMoment += m * accelLeft * h;

        // Semi-implicit Euler: rates first, then positions.
        heaveRate += heaveForce / m * dt;
        pitchRate += pitchMoment / params.pitchInertia() * dt;
        rollRate += rollMoment / params.rollInertia() * dt;
        heave += heaveRate * dt;
        pitch += pitchRate * dt;
        roll += rollRate * dt;
    }

    /** Extra wheel force from the anti-roll bar: pushes the compressed side, lifts the other. */
    private double antiRollForce(int wheel, double halfTrack) {
        double stiffness = wheel < 2 ? params.antiRollFront() : params.antiRollRear();
        double axleForce = stiffness * roll / (2 * halfTrack);
        return wheelY[wheel] < 0 ? axleForce : -axleForce;
    }

    /** Vertical load on a tyre, N. */
    public double load(int wheel) {
        return load[wheel];
    }

    /** Spring compression at a corner relative to rest, m (positive = compressed). */
    public double compression(int wheel) {
        return compression[wheel];
    }

    /** Longitudinal position of a wheel relative to the centre of gravity, m. */
    public double wheelX(int wheel) {
        return wheelX[wheel];
    }

    /** Lateral position of a wheel relative to the centre of gravity (positive = left), m. */
    public double wheelY(int wheel) {
        return wheelY[wheel];
    }

    /** Body pitch, rad (positive = nose down). */
    public double pitch() {
        return pitch;
    }

    /** Body roll, rad (positive = right side down). */
    public double roll() {
        return roll;
    }

    /** Body heave, m (positive = body lower). */
    public double heave() {
        return heave;
    }
}
