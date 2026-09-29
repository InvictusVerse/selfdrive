package com.selfdriving.alerts;

/**
 * One notification from any part of the system.
 *
 * @param id           unique number
 * @param time         simulation time when raised, s
 * @param severity     how serious
 * @param category     what it is about
 * @param message      text for the driver or technician
 * @param source       which subsystem raised it
 * @param acknowledged whether someone has confirmed seeing it (critical alerts stay until then)
 */
public record Alert(long id, double time, Severity severity, Category category, String message, String source,
                    boolean acknowledged) {

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum Category { OBSTACLE, COLLISION, SENSOR, BRAKES, NAVIGATION, AUTOPILOT, BATTERY, SYSTEM }

    Alert withAcknowledged() {
        return new Alert(id, time, severity, category, message, source, true);
    }
}
