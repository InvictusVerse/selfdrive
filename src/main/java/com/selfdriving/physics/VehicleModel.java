package com.selfdriving.physics;

/**
 * The car as a rigid body on a flat road, driven by tyre forces.
 *
 * <p>Every step:
 * <ol>
 *   <li>steering angles per front wheel from Ackermann geometry,</li>
 *   <li>motor torque (with traction control) and brake torque (with ABS) per wheel,</li>
 *   <li>wheel spin updated from drive, brake and tyre torque,</li>
 *   <li>tyre forces from the magic formula, using each tyre's current load,</li>
 *   <li>forces and yaw moment summed with air drag and rolling resistance,</li>
 *   <li>body velocity, yaw rate and position integrated,</li>
 *   <li>suspension advanced, which sets the tyre loads for the next step,</li>
 *   <li>battery energy updated.</li>
 * </ol>
 *
 * <p>The body is integrated in its own frame: {@code x} forward, {@code y} left. World
 * coordinates: {@code x} east, {@code y} north, heading measured counter-clockwise from east.
 *
 * <p>Tyres are very stiff compared with the wheel's inertia, so the wheel spin is solved
 * implicitly (the tyre force is linearised around the current slip). This keeps the model
 * stable at the fixed time step without faking the physics.
 */
public final class VehicleModel {

    public static final int FL = 0;
    public static final int FR = 1;
    public static final int RL = 2;
    public static final int RR = 3;

    /** Physics sub-steps per simulation tick (120 Hz tick -> 960 Hz physics). */
    public static final int SUBSTEPS = 8;

    private static final double PARK_LOCK_TORQUE = 1e5;
    private static final double SLIP_DERIVATIVE_STEP = 1e-3;

    private final VehicleParams params;
    private final ElectricMotor motor;
    private final Battery battery;
    private final Suspension suspension;
    private final Wheel[] wheels = {new Wheel(), new Wheel(), new Wheel(), new Wheel()};
    private final AntiLockBrakes[] abs = {
            new AntiLockBrakes(), new AntiLockBrakes(), new AntiLockBrakes(), new AntiLockBrakes()};
    private final TractionControl tractionControl = new TractionControl();
    /** Same logic as traction control, but for regenerative braking: stops regen locking a wheel. */
    private final TractionControl regenLimiter = new TractionControl();

    private boolean absEnabled = true;
    private boolean tractionControlEnabled = true;

    private double x;
    private double y;
    private double heading;
    private double vx;
    private double vy;
    private double yawRate;
    private double accelForward;
    private double accelLeft;

    private double motorTorque;
    private double motorOmega;
    private double mechanicalPower;
    private double electricalPower;
    private double energyUsedJoules;
    private double distance;

    public VehicleModel(VehicleParams params) {
        this.params = params;
        this.motor = new ElectricMotor(params.motor());
        this.battery = new Battery(params.batteryCapacityKwh(), 0.9);
        this.suspension = new Suspension(params);
    }

    /** Places the car at rest at a position and heading (rad). Battery charge is kept. */
    public void reset(double x, double y, double heading) {
        this.x = x;
        this.y = y;
        this.heading = heading;
        vx = vy = yawRate = accelForward = accelLeft = 0;
        motorTorque = motorOmega = mechanicalPower = electricalPower = 0;
        for (int i = 0; i < 4; i++) {
            wheels[i].reset();
            abs[i].reset();
        }
        tractionControl.reset();
        regenLimiter.reset();
        suspension.reset();
        for (int i = 0; i < 4; i++) {
            wheels[i].load = suspension.load(i);
        }
    }

    /**
     * Sets the car rolling straight ahead at a speed, wheels turning freely. Used by tests and
     * scenarios that start at speed (e.g. a braking test from 100 km/h).
     */
    public void setForwardSpeed(double speed) {
        vx = speed;
        vy = 0;
        yawRate = 0;
        for (Wheel w : wheels) {
            w.omega = speed / params.wheelRadius();
            w.slipRatio = 0;
        }
    }

    /** Advances the car by {@code dt} seconds, split into {@link #SUBSTEPS} physics steps. */
    public void step(double dt, VehicleInputs inputs, Surface surface) {
        double h = dt / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            substep(h, inputs, surface);
        }
    }

    private void substep(double h, VehicleInputs in, Surface surface) {
        double radius = params.wheelRadius();
        double inertia = params.wheelInertia();

        applySteering(in.steerAngle());

        // Motor and traction control (all four wheels are driven).
        double direction = in.gear() == Gear.REVERSE ? -1 : 1;
        double averageOmega = 0;
        double maxSpin = Double.NEGATIVE_INFINITY;
        double maxSkid = Double.NEGATIVE_INFINITY;
        for (Wheel w : wheels) {
            averageOmega += w.omega / 4;
            maxSpin = Math.max(maxSpin, w.slipRatio * direction);
            maxSkid = Math.max(maxSkid, -w.slipRatio * direction);
        }
        motorOmega = averageOmega * motor.gearRatio();
        boolean accelerating = in.throttle() > 0.01;
        double tcsFactor = tractionControl.update(tractionControlEnabled && accelerating, maxSpin, surface, h);
        motorTorque = motor.torque(in.gear(), in.throttle(), motorOmega, vx);
        boolean regenerating = !accelerating && motorTorque * direction < 0;
        double regenFactor = regenLimiter.update(regenerating, maxSkid, surface, h);
        if (accelerating) {
            motorTorque *= tcsFactor;
        } else if (regenerating) {
            motorTorque *= regenFactor;
        }
        if (battery.isEmpty() && motorTorque * direction > 0) {
            motorTorque = 0;
        }
        double wheelTorque = motorTorque * motor.gearRatio();
        double frontTorque = wheelTorque * motor.frontTorqueShare() / 2;
        double rearTorque = wheelTorque * (1 - motor.frontTorqueShare()) / 2;

        double sumFx = 0;
        double sumFy = 0;
        double sumYawMoment = 0;
        for (int i = 0; i < 4; i++) {
            Wheel w = wheels[i];
            double wx = suspension.wheelX(i);
            double wy = suspension.wheelY(i);

            // Velocity of the wheel centre in the car frame, then in the wheel's own frame.
            double vxWheel = vx - yawRate * wy;
            double vyWheel = vy + yawRate * wx;
            double cos = Math.cos(w.steerAngle);
            double sin = Math.sin(w.steerAngle);
            double vLong = vxWheel * cos + vyWheel * sin;
            double vLat = -vxWheel * sin + vyWheel * cos;
            double load = suspension.load(i);

            double maxBrake = i < 2 ? params.maxBrakeTorqueFront() : params.maxBrakeTorqueRear();
            double pressure = abs[i].update(absEnabled, in.brake(), w.slipRatio, vLong, surface, h);
            double brakeTorque = in.brake() * maxBrake * pressure;
            if (in.gear() == Gear.PARK && i >= 2) {
                brakeTorque = PARK_LOCK_TORQUE;
            }
            double driveTorque = i < 2 ? frontTorque : rearTorque;

            w.omega = solveWheelSpin(w.omega, driveTorque, brakeTorque, vLong, vLat, load, surface, h,
                    radius, inertia);
            w.rotation = wrapAngle(w.rotation + w.omega * h);

            TireModel.Force force = TireModel.compute(vLong, vLat, w.omega, radius, load, surface);
            double fxCar = force.longitudinal() * cos - force.lateral() * sin;
            double fyCar = force.longitudinal() * sin + force.lateral() * cos;
            sumFx += fxCar;
            sumFy += fyCar;
            sumYawMoment += wx * fyCar - wy * fxCar;

            w.load = load;
            w.driveTorque = driveTorque;
            w.brakeTorque = brakeTorque;
            w.forceLongitudinal = force.longitudinal();
            w.forceLateral = force.lateral();
            w.forceCarX = fxCar;
            w.forceCarY = fyCar;
            w.slipRatio = TireModel.slipRatio(vLong, w.omega, radius);
            w.slipAngle = TireModel.slipAngle(vLong, vLat);
            w.gripUsage = load > 0
                    ? Math.hypot(force.longitudinal(), force.lateral()) / (surface.friction() * load)
                    : 0;
            w.absActive = abs[i].isActive();
        }

        // Air drag against the direction of travel, rolling resistance against forward motion.
        double speed = Math.hypot(vx, vy);
        double dragFactor = 0.5 * VehicleParams.AIR_DENSITY * params.dragCoefficient()
                * params.frontalArea() * speed;
        double rolling = params.rollingResistance() * params.mass() * VehicleParams.GRAVITY
                * Math.tanh(vx / 0.2);
        double totalFx = sumFx - dragFactor * vx - rolling;
        double totalFy = sumFy - dragFactor * vy;

        accelForward = totalFx / params.mass();
        accelLeft = totalFy / params.mass();

        // Rigid body in a rotating frame (semi-implicit Euler).
        double dvx = (accelForward + yawRate * vy) * h;
        double dvy = (accelLeft - yawRate * vx) * h;
        vx += dvx;
        vy += dvy;
        yawRate += sumYawMoment / params.yawInertia() * h;
        heading = wrapAngle(heading + yawRate * h);
        double cosH = Math.cos(heading);
        double sinH = Math.sin(heading);
        x += (vx * cosH - vy * sinH) * h;
        y += (vx * sinH + vy * cosH) * h;
        distance += Math.hypot(vx, vy) * h;

        suspension.step(h, accelForward, accelLeft);

        mechanicalPower = motorTorque * motorOmega;
        electricalPower = motor.electricalPower(mechanicalPower) + params.auxiliaryPower();
        energyUsedJoules += battery.apply(electricalPower, h);
    }

    /**
     * New wheel speed after one step. The tyre force is linearised around the current spin
     * ({@code F(w1) ~ F(w0) + k (w1 - w0)}) and solved implicitly; the brake, which always
     * opposes rotation, is applied last and can hold the wheel at zero (locked).
     */
    private double solveWheelSpin(double omega, double driveTorque, double brakeTorque,
                                  double vLong, double vLat, double load, Surface surface,
                                  double h, double radius, double inertia) {
        double fx0 = TireModel.compute(vLong, vLat, omega, radius, load, surface).longitudinal();
        double fxPlus = TireModel.compute(vLong, vLat, omega + SLIP_DERIVATIVE_STEP, radius, load, surface)
                .longitudinal();
        double fxMinus = TireModel.compute(vLong, vLat, omega - SLIP_DERIVATIVE_STEP, radius, load, surface)
                .longitudinal();
        double stiffness = Math.max(0, (fxPlus - fxMinus) / (2 * SLIP_DERIVATIVE_STEP));

        double effectiveInertia = inertia + h * stiffness * radius;
        double freeOmega = omega + h * (driveTorque - fx0 * radius) / effectiveInertia;
        double brakeCapacity = h * brakeTorque / effectiveInertia;
        if (Math.abs(freeOmega) <= brakeCapacity) {
            return 0;
        }
        return freeOmega - Math.signum(freeOmega) * brakeCapacity;
    }

    private void applySteering(double steerAngle) {
        double delta = Math.max(-params.maxSteerAngle(), Math.min(params.maxSteerAngle(), steerAngle));
        double left = delta;
        double right = delta;
        if (Math.abs(delta) > 1e-6) {
            // Ackermann: both front wheels point at the same turning centre.
            double turnRadius = params.wheelbase() / Math.tan(delta);
            double halfTrack = params.trackWidth() / 2;
            left = Math.atan(params.wheelbase() / (turnRadius - halfTrack));
            right = Math.atan(params.wheelbase() / (turnRadius + halfTrack));
        }
        wheels[FL].steerAngle = left;
        wheels[FR].steerAngle = right;
        wheels[RL].steerAngle = 0;
        wheels[RR].steerAngle = 0;
    }

    private static double wrapAngle(double angle) {
        double twoPi = 2 * Math.PI;
        angle %= twoPi;
        return angle < 0 ? angle + twoPi : angle;
    }

    // ---- Settings -----------------------------------------------------------------------

    public void setAbsEnabled(boolean enabled) {
        this.absEnabled = enabled;
    }

    public boolean isAbsEnabled() {
        return absEnabled;
    }

    public void setTractionControlEnabled(boolean enabled) {
        this.tractionControlEnabled = enabled;
    }

    public boolean isTractionControlEnabled() {
        return tractionControlEnabled;
    }

    // ---- State --------------------------------------------------------------------------

    public VehicleParams params() {
        return params;
    }

    /** World position east, m. */
    public double x() {
        return x;
    }

    /** World position north, m. */
    public double y() {
        return y;
    }

    /** Heading, rad, counter-clockwise from east, 0..2 pi. */
    public double heading() {
        return heading;
    }

    /** Forward speed in the car frame, m/s (negative when reversing). */
    public double forwardSpeed() {
        return vx;
    }

    /** Sideways speed in the car frame, m/s (positive = sliding left). */
    public double lateralSpeed() {
        return vy;
    }

    /** Speed over ground, m/s. */
    public double speed() {
        return Math.hypot(vx, vy);
    }

    /** Yaw rate, rad/s (positive = turning left). */
    public double yawRate() {
        return yawRate;
    }

    /** Body acceleration forward, m/s^2 (negative when braking). */
    public double accelForward() {
        return accelForward;
    }

    /** Body acceleration to the left, m/s^2. */
    public double accelLeft() {
        return accelLeft;
    }

    /** Sideslip angle of the body, rad: angle between heading and direction of travel. */
    public double sideslipAngle() {
        return Math.abs(vx) < 0.5 ? 0 : Math.atan2(vy, Math.abs(vx));
    }

    public Wheel wheel(int index) {
        return wheels[index];
    }

    public Suspension suspension() {
        return suspension;
    }

    public Battery battery() {
        return battery;
    }

    /** Motor torque, N*m. */
    public double motorTorque() {
        return motorTorque;
    }

    /** Motor speed, rpm. */
    public double motorRpm() {
        return ElectricMotor.toRpm(motorOmega);
    }

    /** Mechanical power at the motor shaft, W (negative = regenerating). */
    public double mechanicalPower() {
        return mechanicalPower;
    }

    /** Electrical power from the battery incl. auxiliary load, W (negative = charging). */
    public double electricalPower() {
        return electricalPower;
    }

    /** Net energy taken from the battery since start, J. */
    public double energyUsedJoules() {
        return energyUsedJoules;
    }

    /** Distance travelled since start, m. */
    public double distance() {
        return distance;
    }

    public boolean isAbsActive() {
        for (AntiLockBrakes unit : abs) {
            if (unit.isActive()) {
                return true;
            }
        }
        return false;
    }

    public boolean isTractionControlActive() {
        return tractionControl.isActive();
    }
}
