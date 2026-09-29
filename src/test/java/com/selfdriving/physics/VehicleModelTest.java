package com.selfdriving.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks the vehicle physics against textbook formulas and real-world figures for a
 * mid-size electric sedan.
 */
class VehicleModelTest {

    private static final double TICK = 1.0 / 120;
    private static final double KMH = 1 / 3.6;
    private static final double G = VehicleParams.GRAVITY;

    private final VehicleParams params = VehicleParams.electricSedan();

    private VehicleModel newCar() {
        VehicleModel car = new VehicleModel(params);
        car.reset(0, 0, 0);
        return car;
    }

    /** Brakes at full pedal from a speed and returns the stopping distance in metres. */
    private static double stoppingDistance(VehicleModel car, double speed, Surface surface) {
        car.setForwardSpeed(speed);
        double startX = car.x();
        VehicleInputs brake = new VehicleInputs(0, 1, 0, Gear.DRIVE);
        for (int i = 0; i < 120 * 60 && car.speed() > 0.01; i++) {
            car.step(TICK, brake, surface);
        }
        return car.x() - startX;
    }

    /**
     * Textbook stopping distance {@code v^2 / (2 mu g)}, with rolling resistance added to the
     * friction coefficient. On dry roads that changes the result by 1 %; on ice (mu = 0.1) the
     * rolling resistance (0.01) is a tenth of the grip, so leaving it out would be wrong.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("Braking distance from 100 km/h matches d = v^2 / (2 (mu + Crr) g)")
    void brakingDistanceMatchesTheory(Surface surface) {
        double v = 100 * KMH;
        double theory = v * v / (2 * (surface.friction() + params.rollingResistance()) * G);

        double measured = stoppingDistance(newCar(), v, surface);

        System.out.printf("%-5s braking 100-0: %.1f m (theory %.1f m, ratio %.3f)%n",
                surface, measured, theory, measured / theory);
        assertTrue(measured > 0.95 * theory && measured < 1.20 * theory,
                () -> String.format("%s: measured %.1f m, theory %.1f m", surface, measured, theory));
    }

    @Test
    @DisplayName("Without ABS the wheels lock and the car needs a longer distance")
    void absShortensStoppingDistance() {
        double v = 100 * KMH;
        double withAbs = stoppingDistance(newCar(), v, Surface.DRY);

        VehicleModel locked = newCar();
        locked.setAbsEnabled(false);
        locked.setForwardSpeed(v);
        VehicleInputs brake = new VehicleInputs(0, 1, 0, Gear.DRIVE);
        for (int i = 0; i < 60; i++) {
            locked.step(TICK, brake, Surface.DRY);
        }
        assertEquals(0, locked.wheel(VehicleModel.FL).omega(), 1e-9, "front-left wheel locked");
        for (int i = 0; i < 120 * 60 && locked.speed() > 0.01; i++) {
            locked.step(TICK, brake, Surface.DRY);
        }
        double withoutAbs = locked.x();

        System.out.printf("ABS on: %.1f m, ABS off: %.1f m%n", withAbs, withoutAbs);
        assertTrue(withoutAbs > withAbs * 1.03, "locked wheels should stop later than ABS");
    }

    @Test
    @DisplayName("0-100 km/h takes 3.8-5.5 s on a dry road")
    void acceleration0To100() {
        VehicleModel car = newCar();
        VehicleInputs flatOut = new VehicleInputs(1, 0, 0, Gear.DRIVE);
        double time = 0;
        while (car.forwardSpeed() < 100 * KMH && time < 20) {
            car.step(TICK, flatOut, Surface.DRY);
            time += TICK;
        }
        System.out.printf("0-100 km/h: %.2f s%n", time);
        assertTrue(time > 3.8 && time < 5.5, "0-100 time " + time);
    }

    @Test
    @DisplayName("Top speed is limited by motor rpm to about 228 km/h")
    void topSpeed() {
        VehicleModel car = newCar();
        VehicleInputs flatOut = new VehicleInputs(1, 0, 0, Gear.DRIVE);
        for (int i = 0; i < 120 * 90; i++) {
            car.step(TICK, flatOut, Surface.DRY);
        }
        double kmh = car.forwardSpeed() / KMH;
        System.out.printf("Top speed: %.1f km/h%n", kmh);
        assertTrue(kmh > 215 && kmh < 235, "top speed " + kmh);
    }

    @Test
    @DisplayName("A parked car does not move")
    void parkedCarStaysStill() {
        VehicleModel car = newCar();
        for (int i = 0; i < 120 * 10; i++) {
            car.step(TICK, VehicleInputs.parked(), Surface.DRY);
        }
        assertEquals(0, car.x(), 1e-3);
        assertEquals(0, car.y(), 1e-3);
        assertEquals(0, car.speed(), 1e-3);
    }

    @Test
    @DisplayName("In Drive with no pedals the car stays still (no creep, no drift)")
    void idleInDriveStaysStill() {
        VehicleModel car = newCar();
        VehicleInputs idle = new VehicleInputs(0, 0, 0, Gear.DRIVE);
        for (int i = 0; i < 120 * 10; i++) {
            car.step(TICK, idle, Surface.DRY);
        }
        assertEquals(0, Math.hypot(car.x(), car.y()), 1e-3);
    }

    @Test
    @DisplayName("At low speed the turning radius follows the steering geometry")
    void lowSpeedTurningRadius() {
        VehicleModel car = newCar();
        double steer = Math.toRadians(15);
        double target = 4.0;
        for (int i = 0; i < 120 * 20; i++) {
            double error = target - car.forwardSpeed();
            double throttle = Math.max(0, Math.min(1, error * 0.3));
            car.step(TICK, new VehicleInputs(throttle, 0, steer, Gear.DRIVE), Surface.DRY);
        }
        double rearAxleRadius = params.wheelbase() / Math.tan(steer);
        double expected = Math.hypot(rearAxleRadius, params.cgToRearAxle());
        double measured = car.speed() / car.yawRate();
        System.out.printf("Turn radius: %.2f m (geometry %.2f m)%n", measured, expected);
        assertEquals(expected, measured, expected * 0.10);
    }

    @Test
    @DisplayName("Cruising at 100 km/h uses a realistic amount of energy")
    void cruiseConsumption() {
        VehicleModel car = newCar();
        car.setForwardSpeed(100 * KMH);
        double startEnergy = car.energyUsedJoules();
        double startDistance = car.distance();
        for (int i = 0; i < 120 * 60; i++) {
            double error = 100 * KMH - car.forwardSpeed();
            double throttle = Math.max(0, Math.min(1, 0.05 + error * 0.2));
            car.step(TICK, new VehicleInputs(throttle, 0, 0, Gear.DRIVE), Surface.DRY);
        }
        double wh = (car.energyUsedJoules() - startEnergy) / 3600;
        double km = (car.distance() - startDistance) / 1000;
        double whPerKm = wh / km;
        System.out.printf("Consumption at 100 km/h: %.0f Wh/km%n", whPerKm);
        assertTrue(whPerKm > 100 && whPerKm < 220, "consumption " + whPerKm);
    }

    @Test
    @DisplayName("Braking moves load to the front wheels and pitches the nose down")
    void brakingTransfersLoadForward() {
        VehicleModel car = newCar();
        car.setForwardSpeed(20);
        for (int i = 0; i < 60; i++) {
            car.step(TICK, new VehicleInputs(0, 0.6, 0, Gear.DRIVE), Surface.DRY);
        }
        double front = car.wheel(VehicleModel.FL).load() + car.wheel(VehicleModel.FR).load();
        double rear = car.wheel(VehicleModel.RL).load() + car.wheel(VehicleModel.RR).load();
        assertTrue(front > rear * 1.3, "front " + front + " rear " + rear);
        assertTrue(car.suspension().pitch() > 0, "nose down");
    }

    @Test
    @DisplayName("A left turn loads the right-hand wheels and rolls the body to the right")
    void corneringTransfersLoadToOutside() {
        VehicleModel car = newCar();
        car.setForwardSpeed(15);
        for (int i = 0; i < 120 * 3; i++) {
            double throttle = Math.max(0, Math.min(1, (15 - car.forwardSpeed()) * 0.3));
            car.step(TICK, new VehicleInputs(throttle, 0, Math.toRadians(4), Gear.DRIVE), Surface.DRY);
        }
        double left = car.wheel(VehicleModel.FL).load() + car.wheel(VehicleModel.RL).load();
        double right = car.wheel(VehicleModel.FR).load() + car.wheel(VehicleModel.RR).load();
        assertTrue(car.yawRate() > 0, "turning left");
        assertTrue(right > left * 1.2, "right " + right + " left " + left);
        assertTrue(car.suspension().roll() > 0, "rolls to the right");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Surface.class)
    @DisplayName("Abuse test: spins, slides and reversing never produce invalid numbers")
    void extremeDrivingStaysStable(Surface surface) {
        VehicleModel car = newCar();
        car.setAbsEnabled(false);
        car.setTractionControlEnabled(false);
        car.setForwardSpeed(40);
        double steer = params.maxSteerAngle();
        VehicleInputs[] phases = {
                new VehicleInputs(1, 0, steer, Gear.DRIVE),
                new VehicleInputs(0, 1, -steer, Gear.DRIVE),
                new VehicleInputs(1, 0, -steer, Gear.DRIVE),
                new VehicleInputs(0, 0, steer, Gear.NEUTRAL),
                new VehicleInputs(1, 0, steer, Gear.REVERSE),
                VehicleInputs.parked()};
        for (VehicleInputs phase : phases) {
            for (int i = 0; i < 120 * 5; i++) {
                car.step(TICK, phase, surface);
                assertTrue(Double.isFinite(car.x()) && Double.isFinite(car.y())
                        && Double.isFinite(car.yawRate()) && Double.isFinite(car.forwardSpeed()),
                        "state became invalid");
                assertTrue(car.speed() < 80, "speed ran away: " + car.speed());
                assertTrue(Math.abs(car.yawRate()) < 10, "yaw rate ran away: " + car.yawRate());
            }
        }
    }

    @Test
    @DisplayName("Lifting off on ice does not lock the wheels with regenerative braking")
    void regenDoesNotLockWheelsOnIce() {
        VehicleModel car = newCar();
        car.setForwardSpeed(20);
        double worstSlip = 0;
        for (int i = 0; i < 120 * 3; i++) {
            car.step(TICK, new VehicleInputs(0, 0, 0, Gear.DRIVE), Surface.ICE);
            for (int w = 0; w < 4; w++) {
                worstSlip = Math.min(worstSlip, car.wheel(w).slipRatio());
            }
        }
        System.out.printf("Worst slip lifting off on ice: %.2f%n", worstSlip);
        assertTrue(worstSlip > -0.8, "wheels must keep turning, worst slip " + worstSlip);
        assertTrue(car.forwardSpeed() < 20, "regen still slows the car");
    }

    @Test
    @DisplayName("Regenerative braking puts energy back into the battery")
    void regenerationChargesBattery() {
        VehicleModel car = newCar();
        car.setForwardSpeed(25);
        double before = car.battery().remainingKwh();
        for (int i = 0; i < 120 * 3; i++) {
            car.step(TICK, new VehicleInputs(0, 0, 0, Gear.DRIVE), Surface.DRY);
        }
        assertTrue(car.forwardSpeed() < 25, "car slowed down");
        assertTrue(car.battery().remainingKwh() > before, "battery gained energy");
    }
}
