package com.selfdriving.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.PasswordHasher;
import com.selfdriving.auth.Role;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.persistence.Database;
import com.selfdriving.persistence.IssueRepository;
import com.selfdriving.persistence.MaintenanceRepository;
import com.selfdriving.persistence.UpdateRepository;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SimulationLoop;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

/** Stage 4 services against a running car: diagnostics, faults, fixes, issues, system tests and updates. */
class MaintenanceAndUpdatesTest {

    private static final World WORLD = new World();

    private final Simulation sim = new Simulation(VehicleParams.electricSedan(), WORLD, new Pose(0, 0, 0));
    private final SimulationLoop loop = new SimulationLoop(sim);
    private final ApplicationContext context = new ApplicationContext(
            Database.inMemory("m" + UUID.randomUUID().toString().replace("-", "")), sim,
            new ServiceTestSupport.TestClock(), new PasswordHasher(1000));

    {
        System.setProperty(ApplicationContext.TRAFFIC_PROPERTY, "0");
        loop.start();
    }

    @AfterEach
    void stop() {
        loop.stop();
        context.close();
        System.clearProperty(ApplicationContext.TRAFFIC_PROPERTY);
    }

    private Session login(String user, String password) {
        return context.login(user, password.toCharArray()).session();
    }

    private Session standardTechnician() {
        Session admin = login("admin", "Admin@2026");
        context.users().create(admin, "tech2", "Standard Tech", "Tech2026x".toCharArray(), Role.TECHNICIAN,
                AccessLevel.STANDARD);
        return login("tech2", "Tech2026x");
    }

    @Test
    @DisplayName("Fault to closed issue: inject, scan opens an issue, start, fix clears the fault, verify, close")
    void issueLifecycle() {
        Session tech = login("tech", "Tech@2026");
        MaintenanceService m = context.maintenance();
        assertTrue(m.scan(tech).report().healthy());

        m.injectFault(tech, Fault.BRAKE_PRESSURE_LOW);
        assertEquals(Set.of(Fault.BRAKE_PRESSURE_LOW), m.activeFaults());
        MaintenanceService.ScanResult scan = m.scan(tech);
        assertEquals(List.of(Fault.BRAKE_PRESSURE_LOW), scan.report().faults());
        assertEquals(1, scan.opened().size());
        assertEquals(0, m.scan(tech).opened().size(), "one issue per fault until it is closed");

        long id = scan.opened().get(0).id();
        assertThrows(ValidationException.class, () -> m.fix(tech, id, null), "must be started first");
        assertEquals(IssueRepository.Status.IN_PROGRESS, m.startWork(tech, id).status());
        IssueRepository.Issue fixed = m.fix(tech, id, null);
        assertEquals(IssueRepository.Status.FIXED, fixed.status());
        assertEquals(Fault.BRAKE_PRESSURE_LOW.fix(), fixed.resolution());
        assertTrue(m.activeFaults().isEmpty(), "the repair cleared the fault");
        assertEquals(IssueRepository.Status.VERIFIED, m.verify(tech, id).status());
        assertEquals(IssueRepository.Status.CLOSED, m.close(tech, id).status());
        assertTrue(m.issues(tech, false).isEmpty());

        List<MaintenanceRepository.Entry> log = m.maintenanceLog(tech, 20);
        assertTrue(log.size() >= 6, "every step is in the maintenance log: " + log.size());
        assertEquals("Priya Nair", log.get(0).technician());
    }

    @Test
    @DisplayName("Verification reopens an issue whose fault came back; manual issues need a note to be fixed")
    void verificationAndManualIssues() {
        Session tech = login("tech", "Tech@2026");
        MaintenanceService m = context.maintenance();
        m.injectFault(tech, Fault.MOTOR_OVERHEAT);
        long id = m.scan(tech).opened().get(0).id();
        m.startWork(tech, id);
        m.fix(tech, id, null);
        m.injectFault(tech, Fault.MOTOR_OVERHEAT); // it overheats again before the check
        assertEquals(IssueRepository.Status.OPEN, m.verify(tech, id).status());

        IssueRepository.Issue manual = m.report(tech, "Steering", "Knocking noise on full lock");
        m.startWork(tech, manual.id());
        assertThrows(ValidationException.class, () -> m.fix(tech, manual.id(), ""));
        assertEquals("Replaced the track rod end", m.fix(tech, manual.id(), "Replaced the track rod end").resolution());
    }

    @Test
    @DisplayName("Permissions: standard technicians cannot inject faults or drive; drivers cannot run diagnostics")
    void permissions() {
        Session standard = standardTechnician();
        assertThrows(AccessDeniedException.class, () -> context.maintenance().injectFault(standard, Fault.LIDAR_OFFLINE));
        assertFalse(standard.can(com.selfdriving.auth.Permission.DRIVE));
        assertNotNull(context.maintenance().scan(standard));
        Session driver = login("driver", "Driver@2026");
        assertThrows(AccessDeniedException.class, () -> context.maintenance().scan(driver));
        assertThrows(AccessDeniedException.class, () -> context.updates().check(driver));
    }

    @Test
    @DisplayName("System tests are saved with their results; a fault shows up as a failed test")
    void systemTests() {
        Session tech = login("tech", "Tech@2026");
        context.maintenance().injectFault(tech, Fault.ULTRASONIC_OFFLINE);
        MaintenanceRepository.TestRun run = context.maintenance().runSystemTests(tech);
        assertEquals(8, run.results().size());
        assertEquals(1, run.failed());
        assertFalse(run.results().get(2).passed());
        assertEquals(run.id(), context.maintenance().testRuns(tech, 5).get(0).id());
    }

    @Test
    @DisplayName("Updates: in order, only when parked; installing changes settings and the version; roll back restores")
    void updates() throws Exception {
        Session admin = login("admin", "Admin@2026");
        UpdateService u = context.updates();
        assertEquals("2.0.0", u.currentVersion());
        List<UpdateRepository.Update> offered = u.check(admin);
        assertEquals(List.of("2.1.0", "2.2.0", "2.3.0"), offered.stream().map(UpdateRepository.Update::version).toList());
        assertThrows(ValidationException.class, () -> u.installNow(admin, offered.get(1).id()), "2.1.0 first");

        // Not while the car is out of Park.
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        Thread.sleep(100);
        assertThrows(ValidationException.class, () -> u.installNow(admin, offered.get(0).id()));
        sim.submit(s -> s.requestGearFromScreen(Gear.PARK));
        Thread.sleep(100);

        UpdateRepository.Update done = u.installNow(admin, offered.get(0).id());
        assertEquals(UpdateRepository.Status.COMPLETED, done.status());
        assertEquals(100, done.progress());
        assertEquals("2.1.0", u.currentVersion());
        assertEquals("1.5", context.settings().get(SettingsService.FOLLOW_TIME_GAP));
        assertFalse(sim.latest().softwareUpdating(), "the car is released after installing");

        assertEquals(UpdateRepository.Status.COMPLETED, u.installNow(admin, offered.get(1).id()).status());
        assertEquals("0.35", context.settings().get(SettingsService.AEB_SENSITIVITY));

        // 2.3.0 arrives damaged the first time: failed checksum, rolled back, nothing changed; a retry works.
        UpdateRepository.Update bad = u.installNow(admin, offered.get(2).id());
        assertEquals(UpdateRepository.Status.ROLLED_BACK, bad.status());
        assertTrue(bad.detail().startsWith("Checksum does not match"), bad.detail());
        assertEquals("2.2.0", u.currentVersion());
        assertEquals("100", context.settings().get(SettingsService.MAX_AUTOPILOT_SPEED));
        assertEquals(UpdateRepository.Status.COMPLETED, u.installNow(admin, offered.get(2).id()).status());
        assertEquals("90", context.settings().get(SettingsService.MAX_AUTOPILOT_SPEED));
        Thread.sleep(100);
        assertEquals(90 / 3.6, sim.latest().settings().maxAutopilotSpeed(), 1e-6, "applied to the car");

        // Roll back newest first.
        assertThrows(ValidationException.class, () -> u.rollBack(admin, offered.get(1).id()));
        assertEquals(UpdateRepository.Status.ROLLED_BACK, u.rollBack(admin, offered.get(2).id()).status());
        assertEquals("2.2.0", u.currentVersion());
        assertEquals("100", context.settings().get(SettingsService.MAX_AUTOPILOT_SPEED));
        Thread.sleep(100);
        assertTrue(sim.latest().alerts().stream().anyMatch(a -> a.category() == Alert.Category.UPDATE));
    }

    @Test
    @DisplayName("The overview counts users, trips, alerts, issues and updates")
    void overview() {
        Session admin = login("admin", "Admin@2026");
        context.updates().check(admin);
        DashboardService.Overview o = context.dashboard().overview(admin);
        assertEquals("2.0.0", o.softwareVersion());
        assertEquals(3, o.updatesAvailable());
        assertEquals(3, o.users());
        assertEquals(0, o.openIssues());
        assertTrue(o.recentAudit().size() > 0);
        assertThrows(AccessDeniedException.class, () -> context.dashboard().overview(login("tech", "Tech@2026")));
    }

    @Test
    @DisplayName("Version numbers compare as numbers; stored previous values survive a round trip")
    void helpers() {
        assertTrue(UpdateService.compare("2.10.0", "2.9.0") > 0);
        assertEquals(0, UpdateService.compare("2.1", "2.1.0"));
        String encoded = UpdateService.encode(new java.util.LinkedHashMap<>(java.util.Map.of("a", "1")));
        assertEquals(java.util.Map.of("a", "1"), UpdateService.decode(encoded));
    }
}
