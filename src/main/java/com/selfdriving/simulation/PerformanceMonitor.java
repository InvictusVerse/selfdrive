package com.selfdriving.simulation;

import java.util.Optional;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;
import com.selfdriving.physics.VehicleParams;

/**
 * Measures the car like a road test would, fully automatically:
 * <ul>
 *   <li><b>Braking test:</b> starts when the brake pedal is fully pressed above 18 km/h and ends
 *       when the car stops. The result is compared with the textbook stopping distance.</li>
 *   <li><b>0-100 km/h:</b> starts when the car pulls away from standstill in Drive and ends at
 *       100 km/h. Braking or leaving Drive cancels it.</li>
 * </ul>
 */
public final class PerformanceMonitor {

    private static final double BRAKE_START_PEDAL = 0.95;
    private static final double BRAKE_START_SPEED = 5.0;
    private static final double BRAKE_ABORT_PEDAL = 0.5;
    private static final double STOPPED_SPEED = 0.25;
    private static final double ACCEL_TARGET_SPEED = 100 / 3.6;

    /**
     * Result of a braking test.
     *
     * @param startSpeed          speed when the brake was pressed, m/s
     * @param distance            distance travelled until stopped, m
     * @param theoreticalDistance {@code v^2 / (2 (mu + Crr) g)}, m
     * @param seconds             time to stop, s
     * @param surface             road surface
     */
    public record BrakeTest(double startSpeed, double distance, double theoreticalDistance,
                            double seconds, Surface surface) {
    }

    /**
     * Result of a 0-100 km/h run.
     *
     * @param seconds time, s
     * @param surface road surface
     */
    public record AccelerationTest(double seconds, Surface surface) {
    }

    private final double rollingResistance;

    private boolean braking;
    private double brakeStartSpeed;
    private double brakeStartDistance;
    private double brakeTime;

    private boolean accelArmed;
    private boolean accelRunning;
    private double accelTime;

    private BrakeTest lastBrakeTest;
    private AccelerationTest lastAccelerationTest;

    public PerformanceMonitor(VehicleParams params) {
        this.rollingResistance = params.rollingResistance();
    }

    /**
     * Updates both tests for one tick.
     *
     * @param dt           time step, s
     * @param forwardSpeed car speed along its heading, m/s
     * @param distance     total distance driven so far, m
     * @param throttle     accelerator, 0..1
     * @param brake        brake pedal, 0..1
     * @param gear         drive selector
     * @param surface      road surface
     * @param driverDriving true when a person is driving (0-100 runs are only timed for the
     *                      driver; braking tests also count emergency braking)
     * @return a message for the driver when a test finishes
     */
    public Optional<String> update(double dt, double forwardSpeed, double distance, double throttle,
                                   double brake, Gear gear, Surface surface, boolean driverDriving) {
        Optional<String> message = updateBrakeTest(dt, forwardSpeed, distance, throttle, brake, surface);
        Optional<String> accel = updateAccelerationTest(dt, forwardSpeed, throttle, brake,
                driverDriving ? gear : Gear.NEUTRAL, surface);
        return message.isPresent() ? message : accel;
    }

    private Optional<String> updateBrakeTest(double dt, double speed, double distance, double throttle,
                                             double brake, Surface surface) {
        if (!braking) {
            if (brake >= BRAKE_START_PEDAL && speed >= BRAKE_START_SPEED) {
                braking = true;
                brakeStartSpeed = speed;
                brakeStartDistance = distance;
                brakeTime = 0;
            }
            return Optional.empty();
        }
        brakeTime += dt;
        if (brake < BRAKE_ABORT_PEDAL || throttle > 0.1) {
            braking = false;
            return Optional.empty();
        }
        if (Math.abs(speed) > STOPPED_SPEED) {
            return Optional.empty();
        }
        braking = false;
        double v = brakeStartSpeed;
        double theory = v * v / (2 * (surface.friction() + rollingResistance) * VehicleParams.GRAVITY);
        lastBrakeTest = new BrakeTest(v, distance - brakeStartDistance, theory, brakeTime, surface);
        return Optional.of(String.format("Braking %.0f \u2192 0 km/h: %.1f m in %.1f s (theory %.1f m)",
                v * 3.6, lastBrakeTest.distance(), brakeTime, theory));
    }

    private Optional<String> updateAccelerationTest(double dt, double speed, double throttle, double brake,
                                                    Gear gear, Surface surface) {
        if (gear != Gear.DRIVE || brake > 0.05) {
            accelArmed = accelRunning = false;
        }
        if (!accelRunning) {
            if (Math.abs(speed) < 0.3 && gear == Gear.DRIVE && brake <= 0.05) {
                accelArmed = true;
            }
            if (accelArmed && throttle > 0.05) {
                accelRunning = true;
                accelArmed = false;
                accelTime = 0;
            }
            return Optional.empty();
        }
        accelTime += dt;
        if (throttle < 0.05) {
            accelRunning = false; // driver lifted off: not a full-throttle run
            return Optional.empty();
        }
        if (speed >= ACCEL_TARGET_SPEED) {
            accelRunning = false;
            lastAccelerationTest = new AccelerationTest(accelTime, surface);
            return Optional.of(String.format("0 \u2192 100 km/h: %.2f s", accelTime));
        }
        return Optional.empty();
    }

    /** True while a braking test is being measured. */
    public boolean isBrakeTestRunning() {
        return braking;
    }

    /** True while a 0-100 km/h run is being timed. */
    public boolean isAccelerationTestRunning() {
        return accelRunning;
    }

    /** Elapsed time of the running 0-100 km/h run, s. */
    public double accelerationTestTime() {
        return accelTime;
    }

    public BrakeTest lastBrakeTest() {
        return lastBrakeTest;
    }

    public AccelerationTest lastAccelerationTest() {
        return lastAccelerationTest;
    }

    public void cancel() {
        braking = accelArmed = accelRunning = false;
    }
}
