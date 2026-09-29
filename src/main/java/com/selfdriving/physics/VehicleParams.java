package com.selfdriving.physics;

/**
 * Physical description of the car. All values are SI units (kg, m, s, N, W).
 *
 * @param mass                  total mass, kg
 * @param yawInertia            moment of inertia about the vertical axis, kg*m^2
 * @param pitchInertia          moment of inertia about the lateral axis, kg*m^2
 * @param rollInertia           moment of inertia about the longitudinal axis, kg*m^2
 * @param wheelbase             distance between front and rear axle, m
 * @param cgToFrontAxle         distance from centre of gravity to the front axle, m
 * @param cgHeight              centre-of-gravity height above the road, m
 * @param trackWidth            distance between left and right wheel centres, m
 * @param wheelRadius           rolling radius, m
 * @param wheelInertia          rotational inertia of one wheel incl. drivetrain share, kg*m^2
 * @param maxSteerAngle         maximum road-wheel angle (bicycle model), rad
 * @param springRate            suspension spring rate per wheel, N/m
 * @param damperRate            suspension damper rate per wheel, N*s/m
 * @param antiRollFront         front anti-roll bar stiffness, N*m/rad
 * @param antiRollRear          rear anti-roll bar stiffness, N*m/rad
 * @param dragCoefficient       aerodynamic drag coefficient Cd
 * @param frontalArea           frontal area, m^2
 * @param rollingResistance     rolling resistance coefficient Crr
 * @param maxBrakeTorqueFront   brake torque per front wheel at full pedal, N*m
 * @param maxBrakeTorqueRear    brake torque per rear wheel at full pedal, N*m
 * @param motor                 electric drivetrain
 * @param batteryCapacityKwh    usable battery energy, kWh
 * @param auxiliaryPower        constant electrical load (computers, lights, climate), W
 */
public record VehicleParams(
        double mass,
        double yawInertia,
        double pitchInertia,
        double rollInertia,
        double wheelbase,
        double cgToFrontAxle,
        double cgHeight,
        double trackWidth,
        double wheelRadius,
        double wheelInertia,
        double maxSteerAngle,
        double springRate,
        double damperRate,
        double antiRollFront,
        double antiRollRear,
        double dragCoefficient,
        double frontalArea,
        double rollingResistance,
        double maxBrakeTorqueFront,
        double maxBrakeTorqueRear,
        MotorParams motor,
        double batteryCapacityKwh,
        double auxiliaryPower) {

    /** Standard gravity, m/s^2. */
    public static final double GRAVITY = 9.81;

    /** Air density at sea level, kg/m^3. */
    public static final double AIR_DENSITY = 1.225;

    /**
     * Electric drivetrain parameters.
     *
     * @param maxTorque         peak motor torque, N*m
     * @param maxPower          peak motor power, W
     * @param gearRatio         fixed reduction gear ratio (motor turns per wheel turn)
     * @param maxRpm            motor speed limit, rpm (sets the top speed)
     * @param frontTorqueShare  share of torque sent to the front axle (0 = rear drive, 0.5 = even)
     * @param regenMaxTorque    peak regenerative braking torque at the motor, N*m
     * @param regenMaxPower     peak regenerative braking power, W
     * @param reverseMaxSpeed   speed limit in reverse, m/s
     * @param driveEfficiency   battery-to-wheel efficiency when driving
     * @param regenEfficiency   wheel-to-battery efficiency when regenerating
     */
    public record MotorParams(
            double maxTorque,
            double maxPower,
            double gearRatio,
            double maxRpm,
            double frontTorqueShare,
            double regenMaxTorque,
            double regenMaxPower,
            double reverseMaxSpeed,
            double driveEfficiency,
            double regenEfficiency) {
    }

    /** Distance from centre of gravity to the rear axle, m. */
    public double cgToRearAxle() {
        return wheelbase - cgToFrontAxle;
    }

    /**
     * A mid-size dual-motor electric sedan (similar in size, mass and performance to
     * popular long-range EV sedans).
     */
    public static VehicleParams electricSedan() {
        MotorParams motor = new MotorParams(
                460,        // max torque, N*m
                300_000,    // max power, W
                9.0,        // gear ratio
                16_000,     // max rpm -> about 228 km/h
                0.4,        // 40 % front / 60 % rear
                200,        // regen torque, N*m
                75_000,     // regen power, W
                7.0,        // reverse limit, m/s (about 25 km/h)
                0.90,
                0.75);
        return new VehicleParams(
                1850,       // mass
                2900,       // yaw inertia
                2600,       // pitch inertia
                650,        // roll inertia
                2.875,      // wheelbase
                1.52,       // CG to front axle -> 47 % front / 53 % rear weight
                0.46,       // CG height
                1.58,       // track width
                0.34,       // wheel radius
                1.4,        // wheel inertia
                Math.toRadians(32),
                35_000,     // spring rate
                3_800,      // damper rate
                45_000,     // front anti-roll bar
                25_000,     // rear anti-roll bar
                0.23,       // drag coefficient
                2.22,       // frontal area
                0.010,      // rolling resistance
                2_600,      // front brake torque per wheel
                1_500,      // rear brake torque per wheel
                motor,
                75,         // battery, kWh
                350);       // auxiliary load, W
    }
}
