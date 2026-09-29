package com.selfdriving.diagnostics;

import com.selfdriving.alerts.Alert;

/**
 * Faults the car can develop (and a technician can inject to test the car's reaction), with
 * diagnostic trouble codes in the usual format: P = powertrain, C = chassis, U = network and
 * sensors.
 *
 * <p>Each fault has a real effect in the simulation, so its symptoms can be seen and the system
 * tests fail until it is fixed.
 */
public enum Fault {

    LIDAR_OFFLINE("U0301", Subsystem.LIDAR, Alert.Severity.CRITICAL,
            "Lidar not responding",
            "No lidar points; the car relies on radar alone ahead and sees nothing to the sides",
            "Reconnect and recalibrate the lidar unit"),
    RADAR_OFFLINE("U0302", Subsystem.RADAR, Alert.Severity.CRITICAL,
            "Front radar not responding",
            "No radar target; distance to the car ahead comes from the lidar only",
            "Clean and realign the front radar"),
    ULTRASONIC_OFFLINE("U0303", Subsystem.ULTRASONIC, Alert.Severity.WARNING,
            "Parking sensors not responding",
            "No close-range distances; automatic parking is unavailable",
            "Replace the ultrasonic sensor harness"),
    BRAKE_PRESSURE_LOW("C1020", Subsystem.BRAKES, Alert.Severity.CRITICAL,
            "Brake pressure low",
            "The friction brakes give 40 % of their normal force: stopping distances are longer",
            "Bleed the brake lines and top up the fluid"),
    MOTOR_OVERHEAT("P0A2F", Subsystem.MOTOR, Alert.Severity.WARNING,
            "Drive motor temperature too high",
            "Motor power limited to 35 % to protect it",
            "Clear the coolant circuit and replace the coolant pump"),
    BATTERY_CELL_IMBALANCE("P0A7F", Subsystem.BATTERY, Alert.Severity.WARNING,
            "Battery cell imbalance",
            "Power limited to 60 % and no regenerative braking until the cells are balanced",
            "Run a cell balancing charge and replace the weak module");

    private final String code;
    private final Subsystem subsystem;
    private final Alert.Severity severity;
    private final String title;
    private final String effect;
    private final String fix;

    Fault(String code, Subsystem subsystem, Alert.Severity severity, String title, String effect, String fix) {
        this.code = code;
        this.subsystem = subsystem;
        this.severity = severity;
        this.title = title;
        this.effect = effect;
        this.fix = fix;
    }

    public String code() {
        return code;
    }

    public Subsystem subsystem() {
        return subsystem;
    }

    public Alert.Severity severity() {
        return severity;
    }

    public String title() {
        return title;
    }

    /** What the driver notices. */
    public String effect() {
        return effect;
    }

    /** The repair a technician applies. */
    public String fix() {
        return fix;
    }

    public static Fault byCode(String code) {
        for (Fault f : values()) {
            if (f.code.equals(code)) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown fault code " + code);
    }
}
