package com.selfdriving.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.selfdriving.alerts.Alert;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

/**
 * Functional tests of the car's systems, run by a technician. Each test drives a separate copy
 * of the car on the empty proving ground (so the car in use is not disturbed), carrying the
 * same faults as the real car, and measures what happens against a pass mark.
 */
public final class SystemTests {

    /**
     * @param name       what was tested
     * @param passed     whether it met the mark
     * @param durationMs how long the test took to run, ms
     * @param message    the measurement and the mark
     */
    public record Result(String name, boolean passed, long durationMs, String message) {
    }

    /** Names in the order they run. */
    public static final List<String> NAMES = List.of("Lidar detection", "Radar tracking", "Parking sensors",
            "Brakes: stop from 50 km/h", "Motor: 0-50 km/h", "Battery: regenerative charging",
            "Emergency braking: stopped car ahead", "Steering response");

    private static final double TICK = SimulationLoop.TICK_SECONDS;
    private static final Pose TEST_START = new Pose(0, 0, 0);
    private static final int TEST_OBJECT = 9_001;

    private final World world;
    private final VehicleParams params;
    private final Set<Fault> faults;

    public SystemTests(World world, VehicleParams params, Set<Fault> faults) {
        this.world = world;
        this.params = params;
        this.faults = Set.copyOf(faults);
    }

    /** Runs every test (a few seconds of computing in all). */
    public List<Result> runAll() {
        List<Result> results = new ArrayList<>();
        results.add(timed(NAMES.get(0), this::lidar));
        results.add(timed(NAMES.get(1), this::radar));
        results.add(timed(NAMES.get(2), this::ultrasonic));
        results.add(timed(NAMES.get(3), this::brakes));
        results.add(timed(NAMES.get(4), this::motor));
        results.add(timed(NAMES.get(5), this::regen));
        results.add(timed(NAMES.get(6), this::emergencyBraking));
        results.add(timed(NAMES.get(7), this::steering));
        return results;
    }

    /** Outcome of one test before timing is added. */
    private record Outcome(boolean passed, String message) {
    }

    private interface Test {
        Outcome run();
    }

    private static Result timed(String name, Test test) {
        long start = System.nanoTime();
        Outcome o;
        try {
            o = test.run();
        } catch (RuntimeException e) {
            o = new Outcome(false, "Test could not run: " + e.getMessage());
        }
        return new Result(name, o.passed(), (System.nanoTime() - start) / 1_000_000, o.message());
    }

    // ---- The tests --------------------------------------------------------------------------

    private Outcome lidar() {
        Simulation sim = car();
        sim.addTestObstacle(box(20, 0));
        run(sim, 0.1);
        SimulationSnapshot s = sim.latest();
        if (!s.sensors().lidarWorking()) {
            return new Outcome(false, "No lidar data");
        }
        int hits = 0;
        float[] angles = s.sensors().lidarAngles();
        float[] ranges = s.sensors().lidarRanges();
        for (int i = 0; i < ranges.length; i++) {
            if (Math.abs(Math.IEEEremainder(angles[i], 2 * Math.PI)) < 0.1 && !Float.isNaN(ranges[i]) && ranges[i] < 25) {
                hits++;
            }
        }
        return new Outcome(hits >= 3, hits + " rays hit the test object 20 m ahead (at least 3 needed)");
    }

    private Outcome radar() {
        Simulation sim = car();
        sim.addTestObstacle(box(40, 0));
        run(sim, 0.1);
        var target = sim.latest().sensors().radar();
        if (target == null || target.obstacleId() != TEST_OBJECT) {
            return new Outcome(false, "No radar target (test object 40 m ahead)");
        }
        double expected = 40 - 1 - 2.38;
        double error = Math.abs(target.range() - expected);
        return new Outcome(error < 0.5, String.format("Range %.2f m, expected %.2f m (within 0.5 m)", target.range(),
                expected));
    }

    private Outcome ultrasonic() {
        Simulation sim = car();
        sim.addTestObstacle(box(-4.5, 0)); // 1.2 m behind the rear bumper sensors
        run(sim, 0.1);
        float[] u = sim.latest().sensors().ultrasonic();
        float left = u[5];
        float right = u[6];
        if (Float.isNaN(left) || Float.isNaN(right)) {
            return new Outcome(false, "Rear centre sensors gave no distance (object 1.2 m behind)");
        }
        boolean ok = Math.abs(left - 1.2) < 0.15 && Math.abs(right - 1.2) < 0.15;
        return new Outcome(ok, String.format("Rear centre sensors %.2f m and %.2f m, expected 1.20 m", left, right));
    }

    private Outcome brakes() {
        Simulation sim = car();
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        run(sim, 0.05);
        double v = 50 / 3.6;
        sim.setSpeedForTest(v);
        double startX = sim.latest().vehicle().x();
        sim.driverInput().setFullBrake(true);
        runUntil(sim, 8, s -> Math.abs(s.vehicle().speed()) < 0.05);
        double distance = sim.latest().vehicle().x() - startX;
        double ideal = v * v / (2 * 1.0 * VehicleParams.GRAVITY);
        double limit = ideal * 1.2;
        return new Outcome(distance <= limit, String.format("Stopped in %.1f m (dry road ideal %.1f m, limit %.1f m)",
                distance, ideal, limit));
    }

    private Outcome motor() {
        Simulation sim = car();
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        run(sim, 0.05);
        sim.driverInput().setAccelerate(true);
        double[] t = {0};
        boolean reached = runUntil(sim, 10, s -> {
            t[0] += TICK;
            return s.vehicle().speed() >= 50 / 3.6;
        });
        return new Outcome(reached && t[0] <= 3.5, reached
                ? String.format("0-50 km/h in %.2f s (limit 3.50 s)", t[0])
                : "Did not reach 50 km/h in 10 s");
    }

    private Outcome regen() {
        Simulation sim = car();
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        run(sim, 0.05);
        sim.setSpeedForTest(60 / 3.6);
        run(sim, 0.8); // coasting: the motor should charge the battery
        double kw = sim.latest().vehicle().batteryPowerKw();
        return new Outcome(kw < -10, String.format("Battery power %.1f kW while coasting from 60 km/h "
                + "(charging at more than 10 kW expected)", kw));
    }

    private Outcome emergencyBraking() {
        Simulation sim = car();
        sim.addTestObstacle(box(45, 0));
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        run(sim, 0.05);
        sim.setSpeedForTest(50 / 3.6);
        sim.driverInput().setAccelerate(true); // an inattentive driver: the car must brake by itself
        boolean[] collided = {false};
        runUntil(sim, 8, s -> {
            collided[0] |= s.alerts().stream().anyMatch(a -> a.category() == Alert.Category.COLLISION);
            return collided[0] || Math.abs(s.vehicle().speed()) < 0.05;
        });
        sim.driverInput().setAccelerate(false);
        double gap = 45 - 1 - 2.38 - sim.latest().vehicle().x();
        if (collided[0]) {
            return new Outcome(false, "Hit the stopped car at 45 m");
        }
        return new Outcome(gap > 0.3, String.format("Stopped %.1f m before the stopped car", gap));
    }

    private Outcome steering() {
        Simulation sim = car();
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        run(sim, 0.05);
        sim.setSpeedForTest(30 / 3.6);
        sim.driverInput().setTouchSteer(0.3);
        run(sim, 1.0);
        double yaw = sim.latest().vehicle().yawRate();
        return new Outcome(yaw > 0.15, String.format("Yaw rate %.2f rad/s at 30 km/h with 30 %% steering "
                + "(at least 0.15 expected)", yaw));
    }

    // ---- Helpers ----------------------------------------------------------------------------

    private Simulation car() {
        Simulation sim = new Simulation(params, world, TEST_START);
        for (Fault f : faults) {
            sim.injectFault(f);
        }
        return sim;
    }

    private static Obstacle box(double x, double y) {
        return new Obstacle(TEST_OBJECT, Obstacle.Kind.CAR, new OrientedBox(x, y, 0, 1.0, 0.9), 1.5, "Test object");
    }

    private static void run(Simulation sim, double seconds) {
        runUntil(sim, seconds, s -> false);
    }

    private static boolean runUntil(Simulation sim, double seconds, Predicate<SimulationSnapshot> done) {
        int ticks = (int) Math.round(seconds / TICK);
        for (int i = 0; i < ticks; i++) {
            sim.processCommands();
            sim.step(TICK);
            if (done.test(sim.latest())) {
                return true;
            }
        }
        return false;
    }
}
