package com.selfdriving.vehicle;

/** Who is driving. */
public enum DriveMode {

    /** The driver controls the car (driver aids and emergency braking still work). */
    MANUAL("MANUAL"),
    /** The autopilot follows the route; touching the brake or steering hands control back. */
    AUTOPILOT("AUTOPILOT"),
    /** The car is braking to a stop because the driver pressed emergency stop. */
    EMERGENCY_STOP("EMERGENCY STOP");

    private final String label;

    DriveMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
