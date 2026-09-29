package com.selfdriving.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.persistence.AlertRepository;
import com.selfdriving.persistence.Database;
import com.selfdriving.persistence.TripRepository;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.world.Place;
import com.selfdriving.world.World;

/** The wired-up system: demo accounts, sign-in, settings reaching the car, trips and alerts saved. */
class ApplicationContextTest {

    private static final World WORLD = new World();

    private final Simulation sim = new Simulation(VehicleParams.electricSedan(), WORLD);
    private final ApplicationContext context = new ApplicationContext(
            Database.inMemory("c" + UUID.randomUUID().toString().replace("-", "")), sim,
            new ServiceTestSupport.TestClock(), new PasswordHasher(1000));

    @AfterEach
    void close() {
        context.close();
    }

    private void tick() {
        sim.processCommands();
        sim.step(1.0 / 120);
    }

    @Test
    @DisplayName("First start: demo accounts exist and can sign in; they are listed until their passwords change")
    void demoAccounts() {
        assertEquals(3, context.unchangedDemoAccounts().size());
        AuthService.Result r = context.login("driver", "Driver@2026".toCharArray());
        assertTrue(r.ok());
        assertEquals(r.session(), context.session());
        context.users().changeOwnPassword(r.session(), "Driver@2026".toCharArray(), "Mine2026x".toCharArray());
        assertEquals(2, context.unchangedDemoAccounts().size());
        context.logout();
        assertNull(context.session());
    }

    @Test
    @DisplayName("Stored settings are applied to the car, and changes reach it straight away")
    void settingsReachTheCar() {
        tick();
        assertEquals(100 / 3.6, sim.latest().settings().maxAutopilotSpeed(), 1e-6);
        Session admin = context.login("admin", "Admin@2026".toCharArray()).session();
        context.settings().set(admin, SettingsService.MAX_AUTOPILOT_SPEED, "70");
        context.settings().set(admin, SettingsService.EMERGENCY_BRAKING, "false");
        tick();
        assertEquals(70 / 3.6, sim.latest().settings().maxAutopilotSpeed(), 1e-6);
        assertFalse(sim.latest().settings().emergencyBrakingEnabled());
    }

    @Test
    @DisplayName("A trip is saved with its driver; alerts are saved and acknowledged by the signed-in user")
    void tripsAndAlertsSaved() {
        Session driver = context.login("driver", "Driver@2026".toCharArray()).session();
        Place place = WORLD.places().stream().filter(p -> !p.name().toLowerCase().contains("proving ground"))
                .findFirst().orElseThrow();
        sim.submit(s -> s.setDestination(place));
        tick();
        sim.submit(Simulation::clearRoute);
        sim.raiseAlert(Alert.Severity.CRITICAL, Alert.Category.SYSTEM, "Test alert", "Test");
        tick();
        long busId = sim.latest().alerts().get(0).id();
        sim.submit(s -> s.acknowledgeAlert(busId));
        tick();
        context.flush();

        List<TripRepository.Trip> trips = context.history().trips(driver, 10);
        assertEquals(1, trips.size());
        assertEquals(place.name(), trips.get(0).destination());
        assertEquals("CANCELLED", trips.get(0).status());
        assertEquals(driver.user().fullName(), trips.get(0).driverName());

        List<AlertRepository.Stored> alerts = context.history().alerts(driver, 10, Alert.Severity.CRITICAL, null);
        assertEquals("Test alert", alerts.get(0).message());
        assertEquals("driver", alerts.get(0).acknowledgedBy());
    }

    @Test
    @DisplayName("Repeated wrong passwords raise a security alert that is saved")
    void securityAlertSaved() {
        for (int i = 0; i < 3; i++) {
            context.login("driver", ("wrong" + i).toCharArray());
        }
        tick();
        context.flush();
        Session admin = context.login("admin", "Admin@2026".toCharArray()).session();
        List<AlertRepository.Stored> security = context.history().alerts(admin, 10, Alert.Severity.INFO,
                Alert.Category.SECURITY);
        assertEquals(1, security.size());
        assertTrue(security.get(0).message().contains("driver"));
    }
}
