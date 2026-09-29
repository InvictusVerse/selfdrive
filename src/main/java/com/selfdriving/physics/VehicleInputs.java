package com.selfdriving.physics;

/**
 * Commands going into the physics for one step, after the driver's (or autopilot's) inputs
 * have been smoothed and checked.
 *
 * @param throttle   accelerator pedal, 0..1
 * @param brake      brake pedal, 0..1
 * @param steerAngle road-wheel angle of an equivalent single front wheel, rad (positive = left)
 * @param gear       drive selector position
 */
public record VehicleInputs(double throttle, double brake, double steerAngle, Gear gear) {

    public VehicleInputs {
        throttle = clamp(throttle, 0, 1);
        brake = clamp(brake, 0, 1);
    }

    /** No pedals, straight wheels, in Park. */
    public static VehicleInputs parked() {
        return new VehicleInputs(0, 0, 0, Gear.PARK);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
