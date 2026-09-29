package com.selfdriving.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.alerts.Alert;
import com.selfdriving.diagnostics.DiagnosticReport;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.diagnostics.Subsystem;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

/** Faults have real effects: diagnostics find them and the system tests that depend on them fail. */
class SystemTestsTest {

    private static final World WORLD = new World();
    private static final VehicleParams PARAMS = VehicleParams.electricSedan();

    private static Set<String> failed(Set<Fault> faults) {
        List<SystemTests.Result> results = new SystemTests(WORLD, PARAMS, faults).runAll();
        results.forEach(r -> System.out.printf("%s %-40s %5d ms  %s%n", faults, r.name(), r.durationMs(), r.message()));
        assertEquals(SystemTests.NAMES, results.stream().map(SystemTests.Result::name).toList());
        return results.stream().filter(r -> !r.passed()).map(SystemTests.Result::name).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("A healthy car passes every system test")
    void healthyCarPasses() {
        assertEquals(Set.of(), failed(Set.of()));
    }

    @Test
    @DisplayName("Each fault fails the tests of the system it affects")
    void faultsFailTheirTests() {
        Map<Fault, String> expected = Map.of(
                Fault.LIDAR_OFFLINE, "Lidar detection",
                Fault.RADAR_OFFLINE, "Radar tracking",
                Fault.ULTRASONIC_OFFLINE, "Parking sensors",
                Fault.BRAKE_PRESSURE_LOW, "Brakes: stop from 50 km/h",
                Fault.MOTOR_OVERHEAT, "Motor: 0-50 km/h",
                Fault.BATTERY_CELL_IMBALANCE, "Battery: regenerative charging");
        for (Map.Entry<Fault, String> e : expected.entrySet()) {
            Set<String> failed = failed(EnumSet.of(e.getKey()));
            assertTrue(failed.contains(e.getValue()), e.getKey() + " should fail " + e.getValue() + ", failed " + failed);
        }
        assertTrue(failed(EnumSet.of(Fault.LIDAR_OFFLINE, Fault.RADAR_OFFLINE))
                .contains("Emergency braking: stopped car ahead"), "no forward sensors: no emergency braking");
    }

    @Test
    @DisplayName("Diagnostics report injected faults with their codes; a repair clears them")
    void diagnostics() {
        Simulation sim = new Simulation(PARAMS, WORLD, new Pose(0, 0, 0));
        assertTrue(sim.diagnose().healthy());
        sim.injectFault(Fault.BRAKE_PRESSURE_LOW);
        sim.injectFault(Fault.MOTOR_OVERHEAT);
        sim.processCommands();
        sim.step(SimulationLoop.TICK_SECONDS);
        DiagnosticReport report = sim.diagnose();
        assertEquals(List.of(Fault.BRAKE_PRESSURE_LOW, Fault.MOTOR_OVERHEAT), report.faults());
        DiagnosticReport.Check brakes = report.checks().stream().filter(c -> c.subsystem() == Subsystem.BRAKES)
                .findFirst().orElseThrow();
        assertEquals(DiagnosticReport.Status.FAULT, brakes.status());
        assertEquals(EnumSet.of(Fault.BRAKE_PRESSURE_LOW, Fault.MOTOR_OVERHEAT), sim.latest().faults());
        assertTrue(sim.latest().alerts().stream().anyMatch(a -> a.message().startsWith("C1020")
                && a.severity() == Alert.Severity.CRITICAL));

        sim.clearFault(Fault.BRAKE_PRESSURE_LOW);
        sim.clearFault(Fault.MOTOR_OVERHEAT);
        assertTrue(sim.diagnose().healthy());
    }

    @Test
    @DisplayName("During a software update the car stays in Park and the autopilot is refused")
    void updateLock() {
        Simulation sim = new Simulation(PARAMS, WORLD, new Pose(0, 0, 0));
        assertTrue(sim.isParkedSafely());
        sim.submit(s -> s.setSoftwareUpdating(true));
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        sim.processCommands();
        assertEquals(Gear.PARK, sim.latest().vehicle().gear());
        assertTrue(sim.latest().softwareUpdating());
        String message;
        boolean told = false;
        while ((message = sim.pollNotification()) != null) {
            told |= message.startsWith("Software update in progress");
        }
        assertTrue(told);
        sim.submit(s -> s.setSoftwareUpdating(false));
        sim.submit(s -> s.requestGearFromScreen(Gear.DRIVE));
        sim.processCommands();
        assertEquals(Gear.DRIVE, sim.latest().vehicle().gear());
        assertFalse(sim.isParkedSafely());
        assertNull(null);
    }
}
