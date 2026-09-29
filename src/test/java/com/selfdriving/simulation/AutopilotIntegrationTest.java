package com.selfdriving.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Comparator;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.navigation.Route;
import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.vehicle.Lights;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.Place;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Pose;
import com.selfdriving.world.RoadNetwork;
import com.selfdriving.world.World;
import com.selfdriving.navigation.RoadGraph;

/**
 * End-to-end drives in the Bengaluru map: the whole simulation (sensors, navigation, autopilot,
 * safety controller, physics, collisions) runs exactly as in the app, only faster than real time.
 */
class AutopilotIntegrationTest {

    private static final double TICK = SimulationLoop.TICK_SECONDS;
    private static final World WORLD = new World();

    private Simulation sim = new Simulation(VehicleParams.electricSedan(), WORLD);
    private boolean collided;
    private double closestApproach = Double.MAX_VALUE;

    /** City places, nearest to the start first. */
    private static Place[] cityPlaces() {
        Pose start = WORLD.start();
        return WORLD.places().stream().filter(p -> !p.name().toLowerCase().contains("proving ground"))
                .sorted(Comparator.comparingDouble(p -> p.location().distanceTo(new Point2(start.x(), start.y()))))
                .toArray(Place[]::new);
    }

    private static Place farPlace() {
        Place[] places = cityPlaces();
        return places[places.length - 1];
    }

    private static Place middlePlace() {
        Place[] places = cityPlaces();
        return places[places.length / 2];
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

    private void startAutopilotTo(Place destination) {
        sim.submit(s -> s.setDestination(destination));
        sim.submit(Simulation::engageAutopilot);
        runUntil(0.1, s -> false);
        assertEquals(DriveMode.AUTOPILOT, sim.latest().mode(), "autopilot engaged to " + destination.name());
    }

    /** Drives until the car is at speed on a straight stretch with room ahead for a test object. */
    private void untilClearStretch(double minSpeed) {
        assertTrue(runUntil(240, s -> s.navigation() != null && s.vehicle().speed() > minSpeed
                && s.navigation().distanceToNext() > 90 && s.navigation().remainingDistance() > 150),
                "reached a clear stretch at " + minSpeed * 3.6 + " km/h");
    }

    @Test
    @DisplayName("Autopilot drives from the proving ground into the city and parks at the kerb")
    void drivesToDestination() {
        sim = new Simulation(VehicleParams.electricSedan(), WORLD, WORLD.provingGroundStart());
        java.util.List<String> started = new java.util.ArrayList<>();
        java.util.List<SimulationListener.TripSummary> ended = new java.util.ArrayList<>();
        sim.addListener(new SimulationListener() {
            @Override
            public void tripStarted(long tripId, String origin, String destination, double plannedM, double etaS) {
                started.add(origin + " -> " + destination);
            }

            @Override
            public void tripEnded(TripSummary summary) {
                ended.add(summary);
            }
        });
        Place place = middlePlace();
        startAutopilotTo(place);
        double length = sim.latest().navigation().route().length();
        assertTrue(length > 1000, "route length " + length);

        int[] turnsChecked = {0};
        boolean arrived = runUntil(600, s -> {
            // Indicating before every turn, on the correct side.
            SimulationSnapshot.Navigation nav = s.navigation();
            boolean inJunction = nav != null && nav.route().stretchAt(nav.arc()).connector() >= 0;
            if (nav != null && !inJunction && nav.distanceToNext() < 30 && nav.distanceToNext() > 3
                    && (nav.nextInstruction().startsWith("Turn left") || nav.nextInstruction().startsWith("Turn right"))) {
                Lights.Indicator expected = nav.nextInstruction().startsWith("Turn left")
                        ? Lights.Indicator.LEFT : Lights.Indicator.RIGHT;
                assertEquals(expected, s.lights().indicator(), nav.nextInstruction());
                turnsChecked[0]++;
            }
            return s.mode() == DriveMode.MANUAL;
        });
        assertTrue(arrived, "arrived within 10 minutes");
        assertTrue(turnsChecked[0] > 0, "the route has turns");
        SimulationSnapshot s = sim.latest();
        s.alerts().forEach(a -> System.out.println("ALERT " + a.message()));
        System.out.printf("END at %.1f, %.1f%n", s.vehicle().x(), s.vehicle().y());
        assertEquals(Gear.PARK, s.vehicle().gear());
        assertNull(s.navigation(), "route finished");
        assertFalse(collided, "no collisions");
        assertTrue(s.alerts().stream().anyMatch(a -> a.message().equals("Arrived at " + place.name())));
        RoadNetwork.Position kerb = WORLD.nearestKerb(place.location());
        Point2 stop = kerb.link().lane(0).pointAt(kerb.arc());
        double distance = Math.hypot(s.vehicle().x() - stop.x(), s.vehicle().y() - stop.y());
        assertTrue(distance < 8, "stopped " + distance + " m from the kerb stop");

        // The trip is reported for saving: from the proving ground, arrived, nearly all on autopilot.
        assertEquals(1, started.size());
        assertTrue(started.get(0).endsWith(" -> " + place.name()), started.get(0));
        assertEquals(1, ended.size());
        SimulationListener.TripSummary trip = ended.get(0);
        System.out.printf("TRIP %s: %.0f m in %.0f s, %.3f kWh, %.0f %% autopilot%n", started.get(0),
                trip.drivenMetres(), trip.durationSeconds(), trip.energyKwh(), trip.autopilotShare() * 100);
        assertEquals(SimulationListener.Outcome.ARRIVED, trip.outcome());
        assertEquals(length, trip.drivenMetres(), length * 0.1, "driven about the route length");
        assertTrue(trip.autopilotShare() > 0.95, "autopilot share " + trip.autopilotShare());
        assertTrue(trip.energyKwh() > 0.05, "energy " + trip.energyKwh());
    }

    @Test
    @DisplayName("Autopilot stays in its lane, respects speed limits and slows for corners")
    void staysInLaneAndBelowLimits() {
        startAutopilotTo(farPlace());
        double[] worstLateral = {0};
        double[] worstOverLimit = {0};
        double[] fastestTurn = {0};
        runUntil(400, s -> {
            if (s.navigation() != null && s.vehicle().speed() > 3) {
                worstOverLimit[0] = Math.max(worstOverLimit[0], s.vehicle().speed() - s.navigation().speedLimit());
                if (s.navigation().arc() > 20) {
                    if (s.navigation().lateralError() > worstLateral[0] + 0.05 && s.navigation().lateralError() > 0.7) {
                        System.out.printf("LATERAL %.2f at %.1f,%.1f speed %.1f km/h yaw %.2f next '%s' in %.0f m%n",
                                s.navigation().lateralError(), s.vehicle().x(), s.vehicle().y(), s.vehicle().speedKmh(),
                                s.vehicle().yawRate(), s.navigation().nextInstruction(), s.navigation().distanceToNext());
                    }
                    worstLateral[0] = Math.max(worstLateral[0], s.navigation().lateralError());
                }
                if (Math.abs(s.vehicle().yawRate()) > 0.25) {
                    fastestTurn[0] = Math.max(fastestTurn[0], s.vehicle().speed());
                }
            }
            return s.mode() == DriveMode.MANUAL;
        });
        System.out.printf("Worst lane error %.2f m, worst over limit %.2f m/s, fastest in a tight turn %.1f km/h%n",
                worstLateral[0], worstOverLimit[0], fastestTurn[0] * 3.6);
        assertTrue(worstOverLimit[0] < 1.5, "speed over limit by " + worstOverLimit[0]);
        assertTrue(worstLateral[0] < 1.0, "lane error " + worstLateral[0]);
        assertTrue(fastestTurn[0] * 3.6 < 32, "tight turns taken at " + fastestTurn[0] * 3.6 + " km/h");
        assertFalse(collided);
    }

    @Test
    @DisplayName("A pedestrian steps out: the car stops in time without touching them")
    void stopsForPedestrian() {
        startAutopilotTo(farPlace());
        untilClearStretch(7);

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
        startAutopilotTo(farPlace());
        untilClearStretch(8);

        sim.submit(Simulation::scenarioStoppedVehicle);
        boolean[] emergency = {false};
        assertTrue(runUntil(25, s -> {
            emergency[0] |= s.safety().emergencyBraking();
            return s.vehicle().speed() < 0.3;
        }), "stopped behind the vehicle");
        double gapWhenStopped = closestApproach;
        assertTrue(runUntil(30, s -> s.vehicle().speed() > 5), "followed when it pulled away");
        System.out.printf("Gap behind stopped vehicle: %.2f m%n", gapWhenStopped);
        assertFalse(collided);
        assertFalse(emergency[0], "the autopilot stops comfortably; emergency braking is not needed");
        assertTrue(gapWhenStopped > 2 && gapWhenStopped < 12, "stopping gap " + gapWhenStopped);
    }

    @Test
    @DisplayName("A stopped vehicle appearing while the car is still pulling away is handled smoothly")
    void stoppedVehicleAtLowSpeed() {
        startAutopilotTo(farPlace());
        assertTrue(runUntil(20, s -> s.vehicle().speed() > 5), "moving");
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
        Place place = farPlace();
        startAutopilotTo(place);
        runUntil(5, s -> false);
        long before = sim.latest().navigation().route().version();

        sim.submit(Simulation::scenarioRoadClosed);
        runUntil(0.1, s -> false);
        SimulationSnapshot s = sim.latest();
        assertNotNull(s.navigation());
        assertNotEquals(before, s.navigation().route().version(), "new route");
        assertFalse(s.closedEdges().isEmpty());
        assertTrue(s.navigation().route().edgeIds().stream().noneMatch(s.closedEdges()::contains));
        assertTrue(s.actors().stream().anyMatch(a -> a.kind() == Obstacle.Kind.BARRIER));

        assertTrue(runUntil(400, x -> x.mode() == DriveMode.MANUAL), "arrived");
        sim.latest().alerts().forEach(a -> System.out.println("ALERT " + a.message()));
        System.out.printf("END at %.1f, %.1f%n", sim.latest().vehicle().x(), sim.latest().vehicle().y());
        assertFalse(collided);
        assertTrue(sim.latest().alerts().stream().anyMatch(a -> a.message().equals("Arrived at " + place.name())));
    }

    @Test
    @DisplayName("Manual driving: emergency braking stops the car for a pedestrian the driver ignores")
    void emergencyBrakingInManualMode() {
        sim = new Simulation(VehicleParams.electricSedan(), WORLD, WORLD.provingGroundStart());
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
    @DisplayName("Through city traffic: stops for red lights, gives way, never hits anyone, and arrives")
    void drivesInTraffic() {
        sim.setTrafficCount(120);
        Place place = farPlace();
        startAutopilotTo(place);
        boolean[] stoppedForRed = {false};
        boolean[] gaveWay = {false};
        boolean[] braking = {false};
        int[] emergencies = {0};
        boolean arrived = runUntil(600, s -> {
            if (s.safety().emergencyBraking() && !braking[0]) {
                emergencies[0]++;
                int threat = s.safety().threatId();
                s.actors().stream().filter(a -> a.id() == threat).findFirst().ifPresent(a -> {
                    double dx = a.box().cx() - s.vehicle().x();
                    double dy = a.box().cy() - s.vehicle().y();
                    double c = Math.cos(s.vehicle().heading());
                    double sn = Math.sin(s.vehicle().heading());
                    System.out.printf("AEB for %s: ahead %.1f m, side %.1f m, its heading %.0f deg relative, "
                                    + "car %.1f km/h, status '%s'%n", a.kind(), dx * c + dy * sn, -dx * sn + dy * c,
                            Math.toDegrees(a.box().heading() - s.vehicle().heading()), s.vehicle().speedKmh(),
                            s.autopilot() == null ? "" : s.autopilot().status());
                });
            }
            braking[0] = s.safety().emergencyBraking();
            if (s.autopilot() != null && s.vehicle().speed() < 0.3) {
                stoppedForRed[0] |= s.autopilot().status().startsWith("Stopping for red");
                gaveWay[0] |= s.autopilot().status().startsWith("Giving way");
            }
            return s.mode() == DriveMode.MANUAL;
        });
        System.out.printf("In traffic: arrived %s, red-light stop %s, gave way %s, red lights run %d%n", arrived,
                stoppedForRed[0], gaveWay[0], sim.redLightsRun());
        sim.latest().alerts().forEach(a -> System.out.println("ALERT " + a.message()));
        assertTrue(arrived, "arrived");
        assertFalse(collided, "no collisions");
        assertEquals(0, sim.redLightsRun(), "never drives through a red light");
        assertEquals(0, emergencies[0], "no emergency braking needed: the autopilot anticipates traffic");
        assertTrue(sim.latest().alerts().stream().anyMatch(a -> a.message().equals("Arrived at " + place.name())));
    }

    @Test
    @DisplayName("A slow vehicle ahead on a multi-lane road is overtaken on the right, with the indicator on")
    void overtakesSlowVehicle() {
        // The longest multi-lane road in the map; drive along it to its far end.
        RoadNetwork.Link road = WORLD.network().links().stream()
                .filter(l -> l.isRendered() && l.lanes() >= 2 && !WORLD.network().junction(l.to()).isBoundary())
                .max(Comparator.comparingDouble(RoadNetwork.Link::length)).orElseThrow();
        assertTrue(road.length() > 200, "long road: " + road.length());
        Point2 from = road.lane(0).pointAt(10);
        sim = new Simulation(VehicleParams.electricSedan(), WORLD, new Pose(from.x(), from.y(), road.lane(0).headingAt(10)));
        startAutopilotTo(new Place("End of " + road.name(), road.lane(0).pointAt(road.length() - 10)));
        assertTrue(runUntil(30, s -> s.vehicle().speed() > 7), "up to speed");
        sim.submit(Simulation::scenarioSlowVehicle);
        runUntil(0.2, s -> false);
        int id = sim.latest().actors().stream().filter(a -> a.kind() == Obstacle.Kind.AUTO_RICKSHAW
                && a.id() < 1000).findFirst().orElseThrow().id();
        boolean[] indicatedRight = {false};
        boolean passed = runUntil(60, s -> {
            indicatedRight[0] |= s.lights().indicator() == Lights.Indicator.RIGHT;
            SimulationSnapshot.ActorState slow = s.actors().stream().filter(a -> a.id() == id).findFirst().orElse(null);
            if (slow == null) {
                return false;
            }
            double dx = slow.box().cx() - s.vehicle().x();
            double dy = slow.box().cy() - s.vehicle().y();
            return dx * Math.cos(s.vehicle().heading()) + dy * Math.sin(s.vehicle().heading()) < -8;
        });
        assertTrue(passed, "passed the slow vehicle");
        assertTrue(indicatedRight[0], "indicated right before pulling out");
        assertFalse(collided, "no collision while overtaking");
    }

    @Test
    @DisplayName("Touching the brake hands control back; emergency stop secures the car")
    void overrideAndEmergencyStop() {
        startAutopilotTo(farPlace());
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
        assertTrue(sim.latest().lights().hazard(), "hazard lights on after an emergency stop");
    }
}
