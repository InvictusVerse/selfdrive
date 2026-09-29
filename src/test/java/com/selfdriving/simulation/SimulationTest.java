package com.selfdriving.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.physics.Gear;
import com.selfdriving.world.Place;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

/** Drives the whole simulation the way the UI does: keys, commands and snapshots. */
class SimulationTest {

    private static final double TICK = SimulationLoop.TICK_SECONDS;
    private static final World WORLD = new World();

    private final Simulation sim = new Simulation(VehicleParams.electricSedan(), WORLD, new Pose(0, 0, 0));

    private void run(double seconds) {
        int ticks = (int) Math.round(seconds / TICK);
        for (int i = 0; i < ticks; i++) {
            sim.processCommands();
            sim.step(TICK);
        }
    }

    private void shiftToDrive() {
        sim.driverInput().setBrake(true);
        run(0.5);
        sim.submit(s -> s.requestGear(Gear.DRIVE));
        run(0.1);
        sim.driverInput().setBrake(false);
        run(0.5);
    }

    @Test
    @DisplayName("Shifting out of Park without the brake is refused with a message")
    void shiftWithoutBrakeIsRefused() {
        sim.submit(s -> s.requestGear(Gear.DRIVE));
        run(0.1);
        assertEquals(Gear.PARK, sim.latest().vehicle().gear());
        assertNotNull(sim.pollNotification());
    }

    @Test
    @DisplayName("A tap on the screen shifts out of Park while stopped (the car holds the brake)")
    void screenTapShiftsWhileStopped() {
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        run(0.1);
        assertEquals(Gear.DRIVE, sim.latest().vehicle().gear());
    }

    @Test
    @DisplayName("On-screen pedal and wheel drive the car; letting go of the wheel centres it")
    void touchControlsDriveTheCar() {
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        sim.driverInput().setTouchAccelerate(true);
        sim.driverInput().setTouchSteer(0.5);
        run(3);
        assertTrue(sim.latest().vehicle().speed() > 10);
        assertEquals(0.5, sim.latest().vehicle().steerInput(), 1e-9);
        assertTrue(sim.latest().vehicle().yawRate() > 0, "turning left");

        // Releasing the keyboard must not release the on-screen pedal.
        sim.driverInput().setAccelerate(false);
        sim.driverInput().setTouchSteer(Double.NaN);
        run(1);
        assertEquals(0, sim.latest().vehicle().steerInput(), 1e-9);
        assertTrue(sim.latest().vehicle().throttle() > 0.99);
    }

    @Test
    @DisplayName("Holding the accelerator in Drive accelerates the car and times 0-100 km/h")
    void accelerateAndTime() {
        shiftToDrive();
        assertEquals(Gear.DRIVE, sim.latest().vehicle().gear());

        sim.driverInput().setAccelerate(true);
        run(8);
        SimulationSnapshot snapshot = sim.latest();
        assertTrue(snapshot.vehicle().speedKmh() > 100, "speed " + snapshot.vehicle().speedKmh());
        assertNotNull(snapshot.lastAccelTest(), "0-100 run recorded");
        double seconds = snapshot.lastAccelTest().seconds();
        assertTrue(seconds > 4 && seconds < 6, "0-100 incl. pedal ramp: " + seconds);
    }

    @Test
    @DisplayName("A full stop from speed is recorded as a braking test")
    void brakingTestIsRecorded() {
        shiftToDrive();
        sim.driverInput().setAccelerate(true);
        run(6);
        sim.driverInput().setAccelerate(false);
        assertNull(sim.latest().lastBrakeTest());

        sim.driverInput().setFullBrake(true);
        run(6);
        PerformanceMonitor.BrakeTest test = sim.latest().lastBrakeTest();
        assertNotNull(test);
        assertTrue(test.distance() > test.theoreticalDistance() * 0.9, "distance " + test.distance());
        assertTrue(test.distance() < test.theoreticalDistance() * 1.35, "distance " + test.distance());
    }

    @Test
    @DisplayName("Reset puts the car back at the start in Park")
    void resetReturnsToStart() {
        shiftToDrive();
        sim.driverInput().setAccelerate(true);
        run(3);
        sim.driverInput().setAccelerate(false);
        sim.submit(Simulation::resetCar);
        run(0.1);
        assertEquals(0, sim.latest().vehicle().x(), 1e-6);
        assertEquals(Gear.PARK, sim.latest().vehicle().gear());
    }

    @Test
    @DisplayName("Trips: a new destination or a cancelled route ends the trip as cancelled; alerts are reported")
    void tripAndAlertEvents() {
        Simulation city = new Simulation(VehicleParams.electricSedan(), WORLD);
        List<String> events = new ArrayList<>();
        city.addListener(new SimulationListener() {
            @Override
            public void tripStarted(long tripId, String origin, String destination, double plannedM, double etaS) {
                assertTrue(plannedM > 0 && etaS > 0);
                events.add("start " + tripId + " " + destination);
            }

            @Override
            public void tripEnded(TripSummary summary) {
                events.add("end " + summary.tripId() + " " + summary.outcome());
            }

            @Override
            public void alertRaised(Alert alert) {
                events.add("alert " + alert.category());
            }

            @Override
            public void alertAcknowledged(long alertId) {
                events.add("ack " + alertId);
            }
        });
        List<Place> places = WORLD.places().stream()
                .filter(p -> !p.name().toLowerCase().contains("proving ground")).toList();
        city.submit(s -> s.setDestination(places.get(0)));
        city.processCommands();
        city.submit(s -> s.setDestination(places.get(1)));
        city.processCommands();
        city.submit(Simulation::clearRoute);
        city.processCommands();
        city.raiseAlert(Alert.Severity.WARNING, Alert.Category.SECURITY, "Repeated failed logins", "Login");
        city.processCommands();
        long id = city.latest().alerts().get(0).id();
        city.submit(s -> s.acknowledgeAlert(id));
        city.processCommands();

        assertEquals(List.of("start 1 " + places.get(0).name(), "end 1 CANCELLED", "start 2 " + places.get(1).name(),
                "end 2 CANCELLED", "alert SECURITY", "ack " + id), events);
    }
}
