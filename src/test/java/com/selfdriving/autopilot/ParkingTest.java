package com.selfdriving.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SimulationLoop;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

class ParkingTest {

    private static final World WORLD = new World();
    private static final double TICK = SimulationLoop.TICK_SECONDS;

    @Test
    @DisplayName("The two-arc manoeuvre moves the car sideways by 2R(1 - cos theta) and back by 2R sin theta")
    void geometry() {
        double r = ParkingPlanner.RADIUS;
        double theta = Math.toRadians(35);
        ParkingPlanner.Pose start = new ParkingPlanner.Pose(0, 0, 0);
        ParkingPlanner.Pose mid = ParkingPlanner.advance(start, 1 / r, -r * theta);
        ParkingPlanner.Pose end = ParkingPlanner.advance(mid, -1 / r, -r * theta);
        assertEquals(0, end.heading(), 1e-9, "straight again");
        assertEquals(2 * r * (1 - Math.cos(theta)), end.y(), 1e-9, "sideways, to the left");
        assertEquals(-2 * r * Math.sin(theta), end.x(), 1e-9, "backwards");
        ParkingPlanner.Pose back = ParkingPlanner.backwards(end,
                List.of(new ParkingPlanner.Segment(1 / r, -r * theta), new ParkingPlanner.Segment(-1 / r, -r * theta)));
        assertEquals(0, back.x(), 1e-9);
        assertEquals(0, back.y(), 1e-9);
    }

    private static Simulation parkFrom(Pose start) {
        Simulation sim = new Simulation(VehicleParams.electricSedan(), WORLD, start);
        for (int i = 0; i < 30; i++) { // one sensor scan
            sim.processCommands();
            sim.step(TICK);
        }
        sim.submit(Simulation::autoPark);
        return sim;
    }

    /** Runs until parking ends; returns {used reverse, collided}. */
    private static boolean[] run(Simulation sim, double seconds) {
        boolean reversed = false;
        boolean collided = false;
        boolean started = false;
        for (int i = 0; i < seconds / TICK; i++) {
            sim.processCommands();
            sim.step(TICK);
            SimulationSnapshot s = sim.latest();
            started |= s.mode() == DriveMode.AUTO_PARK;
            reversed |= s.vehicle().gear() == Gear.REVERSE && s.vehicle().forwardSpeed() < -0.2;
            collided |= s.alerts().stream().anyMatch(a -> a.category() == Alert.Category.COLLISION);
            if (started && s.mode() != DriveMode.AUTO_PARK) {
                break;
            }
        }
        assertTrue(started, "parking started");
        return new boolean[] {reversed, collided};
    }

    private static void assertInSpace(Simulation sim, OrientedBox space, double maxAngleDeg) {
        SimulationSnapshot s = sim.latest();
        double c = Math.cos(s.vehicle().heading());
        double sn = Math.sin(s.vehicle().heading());
        double bx = s.vehicle().x() + c * CarBody.centreOffset();
        double by = s.vehicle().y() + sn * CarBody.centreOffset();
        double dx = bx - space.cx();
        double dy = by - space.cy();
        double along = dx * Math.cos(space.heading()) + dy * Math.sin(space.heading());
        double across = -dx * Math.sin(space.heading()) + dy * Math.cos(space.heading());
        double angle = Math.toDegrees(Math.atan2(Math.sin(s.vehicle().heading() - space.heading()),
                Math.cos(s.vehicle().heading() - space.heading())));
        System.out.printf("Parked: %.2f m along, %.2f m across, %.1f deg from the space%n", along, across, angle);
        assertEquals(DriveMode.MANUAL, s.mode());
        assertEquals(Gear.PARK, s.vehicle().gear());
        assertTrue(s.alerts().stream().anyMatch(a -> a.message().equals("Parked")), "parked");
        assertTrue(Math.abs(across) < 0.3, "centred across the space: " + across);
        assertTrue(Math.abs(along) < 0.5, "centred along the space: " + along);
        assertTrue(Math.abs(angle) < maxAngleDeg, "straight in the space: " + angle);
    }

    @Test
    @DisplayName("Parallel parking between two parked cars, in reverse, without touching them")
    void parallelParking() {
        Simulation sim = parkFrom(WORLD.parkingArea().approach());
        sim.processCommands();
        OrientedBox space = sim.parkingSpace();
        assertNotNull(space, "found the free space");
        boolean[] result = run(sim, 90);
        assertTrue(result[0], "reversed into the space");
        assertFalse(result[1], "no collision");
        assertInSpace(sim, space, 3);
    }

    @Test
    @DisplayName("Reversing into a free bay between parked cars")
    void bayParking() {
        Pose approach = WORLD.parkingArea().approach();
        Simulation sim = parkFrom(new Pose(approach.x(), 44, approach.heading()));
        sim.processCommands();
        OrientedBox space = sim.parkingSpace();
        assertNotNull(space, "found a free bay");
        boolean[] result = run(sim, 90);
        assertTrue(result[0], "reversed into the bay");
        assertFalse(result[1], "no collision");
        assertInSpace(sim, space, 3);
    }
}
