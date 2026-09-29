package com.selfdriving.diagnostics;

/** Parts of the car that diagnostics check. */
public enum Subsystem {
    LIDAR("Lidar"),
    RADAR("Front radar"),
    ULTRASONIC("Parking sensors"),
    BRAKES("Brakes"),
    MOTOR("Drive motor"),
    BATTERY("Battery"),
    STEERING("Steering");

    private final String label;

    Subsystem(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
