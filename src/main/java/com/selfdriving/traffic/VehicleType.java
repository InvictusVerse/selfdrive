package com.selfdriving.traffic;

import com.selfdriving.world.Obstacle;

/**
 * Kinds of road users in an Indian city, with their size and driving behaviour (Intelligent
 * Driver Model parameters: desired acceleration, comfortable braking, time gap, minimum gap).
 *
 * @param kind        what sensors report
 * @param label       display name
 * @param length      m
 * @param width       m
 * @param height      m
 * @param maxSpeed    top speed in town, m/s
 * @param accel       comfortable acceleration a, m/s^2
 * @param decel       comfortable braking b, m/s^2
 * @param timeGap     time gap T kept to the vehicle ahead, s
 * @param minGap      standstill gap s0, m
 * @param share       share of traffic (weights for spawning)
 */
public record VehicleType(Obstacle.Kind kind, String label, double length, double width, double height,
                          double maxSpeed, double accel, double decel, double timeGap, double minGap,
                          double share) {

    public static final VehicleType CAR = new VehicleType(Obstacle.Kind.CAR, "Car", 4.3, 1.75, 1.5,
            60 / 3.6, 1.6, 2.2, 1.3, 2.2, 0.36);
    public static final VehicleType SUV = new VehicleType(Obstacle.Kind.CAR, "SUV", 4.7, 1.9, 1.8,
            60 / 3.6, 1.5, 2.2, 1.4, 2.3, 0.12);
    public static final VehicleType AUTO_RICKSHAW = new VehicleType(Obstacle.Kind.AUTO_RICKSHAW, "Auto-rickshaw",
            2.9, 1.4, 1.75, 42 / 3.6, 1.1, 2.0, 1.1, 1.8, 0.16);
    public static final VehicleType MOTORBIKE = new VehicleType(Obstacle.Kind.MOTORBIKE, "Motorbike", 2.0, 0.8,
            1.4, 60 / 3.6, 2.3, 2.8, 1.0, 1.5, 0.24);
    public static final VehicleType BUS = new VehicleType(Obstacle.Kind.BUS, "Bus", 11.5, 2.55, 3.2,
            45 / 3.6, 0.8, 1.6, 1.8, 3.0, 0.06);
    public static final VehicleType TRUCK = new VehicleType(Obstacle.Kind.TRUCK, "Truck", 8.5, 2.45, 3.2,
            45 / 3.6, 0.7, 1.6, 1.8, 3.0, 0.06);

    public static final VehicleType[] ALL = {CAR, SUV, AUTO_RICKSHAW, MOTORBIKE, BUS, TRUCK};

    /** Picks a type by traffic share from a number in 0..1. */
    public static VehicleType pick(double u) {
        double total = 0;
        for (VehicleType t : ALL) {
            total += t.share;
        }
        double at = u * total;
        for (VehicleType t : ALL) {
            at -= t.share;
            if (at <= 0) {
                return t;
            }
        }
        return CAR;
    }

    /** True for long vehicles that keep to the left lanes and cannot squeeze past. */
    public boolean isHeavy() {
        return kind == Obstacle.Kind.BUS || kind == Obstacle.Kind.TRUCK;
    }
}
