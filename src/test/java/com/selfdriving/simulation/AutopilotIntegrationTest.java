package com.selfdriving.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.Place;
import com.selfdriving.world.World;

/**
 * End-to-end drives: the whole simulation (sensors, navigation, autopilot, safety controller,
 * physics, collisions) runs exactly as in the app, only faster than real time.
 */
class AutopilotIntegrationTest {

    private static final double TICK = SimulationLoop.TICK_SECONDS;
    private static final World WORLD = new World();

    private final Simulation sim = new Simulation(VehicleParams.electricSedan(), WORLD);
    private boolean collided;
    private double closestApproach = Double.MAX_VALUE;

    private static Place place(String name) {
        return WORLD.places().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
    }

    /** Runs until the condition holds or the time runs out; returns whether it held. */
    private boolean runUntil(double maxSeconds, Predicate<SimulationSnapshot> condition) {
        int ticks = (int) Math.round(maxSeconds / TICK);
        for (int i = 0; i < ticks; i++) {
            sim.processCommands();
            sim.step(TICK);
            SimulationSnapshot s = sim.latest();
            collided |= s.alerts().stream().anyMatch(a -> a.category() == Alert.Category.COLLISION);
            for (SimulationSnapshot.ActorState actor : s.actors()) {
                double gap = gapTo(s, actor);
                closestApproach = Math.min(closestApproach, gap);
            }
            if (condition.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** Distance between the car body and an actor's footprint (approximate, via centres). */
    private static double gapTo(SimulationSnapshot s, SimulationSnapshot.ActorState actor) {
        double dx = actor.box().cx() - s.vehicle().x();
        double dy = actor.box().cy() - s.vehicle().y();
        double c = Math.cos(s.vehicle().heading());
        double sn = Math.sin(s.vehicle().heading());
        double ahead = dx * c + dy * sn;
        double side = -dx * sn + dy * c;
        double gx = Math.max(0, Math.abs(ahead - CarBody.centreOffset()) - CarBody.halfLength()
                - Math.max(actor.box().halfLength(), actor.box().halfWidth()));
        double gy = Math.max(0, Math.abs(side) - CarBody.HALF_WIDTH
                - Math.max(actor.box().halfLength(), actor.box().halfWidth()));
        return Math.hypot(gx, gy);
    }

    private void startAutopilotTo(String destination) {
        sim.submit(s -> s.setDestination(place(destination)));
        sim.submit(Simulation::engageAutopilot);
        runUntil(0.1, s -> false);
        assertEquals(DriveMode.AUTOPILOT, sim.latest().mode(), "autopilot engaged");
    }

    @Test
    @DisplayName("Autopilot drives from the proving ground to Central Station and parks")
    void drivesToDestination() {
        startAutopilotTo("Central Station");
        double length = sim.latest().navigation().route().length();
        assertTrue(length > 1000, "route length " + length);

        boolean arrived = runUntil(300, s -> s.mode() == DriveMode.MANUAL);
        assertTrue(arrived, "arrived within 5 minutes");
        SimulationSnapshot s = sim.latest();
        assertEquals(Gear.PARK, s.vehicle().gear());
        assertNull(s.navigation(), "route finished");
        assertFalse(collided, "no collisions");
        assertTrue(s.alerts().stream().anyMatch(a -> a.message().equals("Arrived at Central Station")));
        double distance = Math.hypot(s.vehicle().x() - place("Central Station").location().x(),
                s.vehicle().y() - place("Central Station").location().y());
        assertTrue(distance < 12, "stopped " + distance + " m from the junction");
    }

    @Test
    @DisplayName("Autopilot stays in its lane and respects the speed limits")
    void staysInLaneAndBelowLimits() {
        startAutopilotTo("Market Square");
        double[] worstLateral = {0};
        double[] worstOverLimit = {0};
        runUntil(300, s -> {
            if (s.navigation() != null && s.vehicle().speed() > 3) {
                worstOverLimit[0] = Math.max(worstOverLimit[0], s.vehicle().speed() - s.navigation().speedLimit());
                if (s.navigation().arc() > 20) {
                    worstLateral[0] = Math.max(worstLateral[0], s.navigation().lateralError());
                }
            }
            return s.mode() == DriveMode.MANUAL;
        });
        System.out.printf("Worst lane error %.2f m, worst over limit %.2f m/s%n", worstLateral[0], worstOverLimit[0]);
        assertTrue(worstOverLimit[0] < 1.5, "speed over limit by " + worstOverLimit[0]);
        assertTrue(worstLateral[0] < 1.0, "lane error " + worstLateral[0]);
        assertFalse(collided);
    }

    @Test
    @DisplayName("A pedestrian steps out in town: the car stops in time without touching them")
    void stopsForPedestrian() {
        startAutopilotTo("Market Square");
        assertTrue(runUntil(200, s -> s.vehicle().y() > 450 && s.vehicle().speed() > 12), "reached town speed");

        sim.submit(Simulation::scenarioPedestrian);
        boolean stopped = runUntil(12, s -> s.vehicle().speed() < 0.3);
        assertTrue(stopped, "car stopped");
        runUntil(15, s -> s.actors().isEmpty());
        System.out.printf("Closest approach to pedestrian: %.2f m%n", closestApproach);
        assertFalse(collided, "no collision");
        assertTrue(closestApproach > 0.3, "kept a gap of " + closestApproach);
        assertTrue(runUntil(30, s -> s.vehicle().speed() > 5), "drives on once the road is clear");
    }

    @Test
    @DisplayName("A stopped vehicle ahead: the car stops behind it, then follows when it pulls away")
    void followsStoppedVehicle() {
        startAutopilotTo("Central Station");
        assertTrue(runUntil(60, s -> s.vehicle().speed() > 20), "up to circuit speed");

        sim.submit(Simulation::scenarioStoppedVehicle);
        boolean[] emergency = {false};
        assertTrue(runUntil(20, s -> {
            emergency[0] |= s.safety().emergencyBraking();
            return s.vehicle().speed() < 0.3;
        }), "stopped behind the vehicle");
        double gapWhenStopped = closestApproach;
        assertTrue(runUntil(30, s -> s.vehicle().speed() > 6), "followed when it pulled away");
        System.out.printf("Gap behind stopped vehicle: %.2f m%n", gapWhenStopped);
        assertFalse(collided);
        assertFalse(emergency[0], "the autopilot stops comfortably; emergency braking is not needed");
        assertTrue(gapWhenStopped > 2 && gapWhenStopped < 12, "stopping gap " + gapWhenStopped);
    }

    @Test
    @DisplayName("A stopped vehicle appearing while the car is still pulling away is handled smoothly")
    void stoppedVehicleAtLowSpeed() {
        startAutopilotTo("Central Station");
        assertTrue(runUntil(20, s -> s.vehicle().speed() > 6), "moving");
        sim.submit(Simulation::scenarioStoppedVehicle);
        boolean[] emergency = {false};
        assertTrue(runUntil(25, s -> {
            emergency[0] |= s.safety().emergencyBraking();
            return s.vehicle().speed() < 0.3;
        }), "stopped");
        assertFalse(collided);
        assertFalse(emergency[0], "no emergency braking needed");
    }

    @Test
    @DisplayName("Road closed ahead: the route changes to avoid it, and the car still arrives")
    void reroutesAroundClosure() {
        startAutopilotTo("Central Station");
        assertTrue(runUntil(200, s -> s.vehicle().y() > 430), "in town");
        long before = sim.latest().navigation().route().version();

        sim.submit(Simulation::scenarioRoadClosed);
        runUntil(0.1, s -> false);
        SimulationSnapshot s = sim.latest();
        assertNotNull(s.navigation());
        assertNotEquals(before, s.navigation().route().version(), "new route");
        assertFalse(s.closedEdges().isEmpty());
        assertTrue(s.navigation().route().edgeIds().stream().noneMatch(s.closedEdges()::contains));
        assertTrue(s.actors().stream().anyMatch(a -> a.kind() == Obstacle.Kind.BARRIER));

        assertTrue(runUntil(300, x -> x.mode() == DriveMode.MANUAL), "arrived");
        assertFalse(collided);
        assertTrue(sim.latest().alerts().stream().anyMatch(a -> a.message().equals("Arrived at Central Station")));
    }

    @Test
    @DisplayName("Manual driving: emergency braking stops the car for a pedestrian the driver ignores")
    void emergencyBrakingInManualMode() {
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        runUntil(0.1, s -> false);
        sim.setSpeedForTest(50 / 3.6);
        sim.driverInput().setAccelerate(true); // the driver is not paying attention
        runUntil(0.2, s -> false);
        sim.submit(Simulation::scenarioPedestrian);

        boolean braked = runUntil(8, s -> s.safety().emergencyBraking());
        assertTrue(braked, "emergency braking triggered");
        assertTrue(runUntil(8, s -> s.vehicle().speed() < 0.3), "car stopped");
        runUntil(10, s -> s.actors().isEmpty());
        assertFalse(collided, "no collision");
        assertTrue(sim.latest().alerts().stream().anyMatch(a -> a.message().startsWith("Emergency braking")));
    }

    @Test
    @DisplayName("Touching the brake hands control back; emergency stop secures the car")
    void overrideAndEmergencyStop() {
        startAutopilotTo("Central Station");
        runUntil(10, s -> false);
        sim.driverInput().setTouchBrake(true);
        runUntil(0.1, s -> false);
        assertEquals(DriveMode.MANUAL, sim.latest().mode(), "driver override");
        sim.driverInput().setTouchBrake(false);

        sim.submit(Simulation::engageAutopilot);
        runUntil(8, s -> false);
        assertEquals(DriveMode.AUTOPILOT, sim.latest().mode());
        sim.submit(Simulation::emergencyStop);
        assertTrue(runUntil(10, s -> s.mode() == DriveMode.MANUAL), "emergency stop finished");
        assertEquals(Gear.PARK, sim.latest().vehicle().gear());
        assertTrue(sim.latest().vehicle().speed() < 0.3);
    }
}
