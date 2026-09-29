package com.selfdriving.sensors;

import java.util.List;

import com.selfdriving.world.Obstacle;

/**
 * Everything the sensors reported in one scan. Immutable, so it can go into snapshots.
 *
 * @param lidarAngles   ray directions relative to the car's heading, rad
 * @param lidarRanges   distance per ray, m ({@link Float#NaN} = nothing within range)
 * @param radar         nearest object ahead, or null
 * @param ultrasonic    distance per parking sensor, m (NaN = nothing within range); order as in
 *                      {@link SensorSuite#ULTRASONIC_NAMES}
 * @param detected      objects seen by lidar or radar (not buildings)
 * @param lidarWorking  lidar healthy
 * @param radarWorking  radar healthy
 * @param ultrasonicWorking parking sensors healthy
 */
public record SensorReadings(
        float[] lidarAngles,
        float[] lidarRanges,
        RadarTarget radar,
        float[] ultrasonic,
        List<DetectedObject> detected,
        boolean lidarWorking,
        boolean radarWorking,
        boolean ultrasonicWorking) {

    public SensorReadings {
        detected = List.copyOf(detected);
    }

    /**
     * The radar's nearest target.
     *
     * @param range        distance, m
     * @param closingSpeed how fast the gap is shrinking, m/s (positive = getting closer)
     * @param obstacleId   id of the object
     */
    public record RadarTarget(double range, double closingSpeed, int obstacleId) {
    }

    /**
     * An object the car knows about (position measured by the sensors).
     *
     * @param id       obstacle id
     * @param kind     what it is
     * @param x        centre east, m
     * @param y        centre north, m
     * @param heading  rad
     * @param halfLength m
     * @param halfWidth  m
     * @param vx       velocity east, m/s
     * @param vy       velocity north, m/s
     * @param distance distance from the car, m
     */
    public record DetectedObject(int id, Obstacle.Kind kind, double x, double y, double heading,
                                 double halfLength, double halfWidth, double vx, double vy, double distance) {
    }

    /** Smallest parking-sensor distance, m (NaN if nothing close). */
    public double nearestUltrasonic() {
        double best = Double.NaN;
        for (float d : ultrasonic) {
            if (!Float.isNaN(d) && (Double.isNaN(best) || d < best)) {
                best = d;
            }
        }
        return best;
    }

    public static SensorReadings empty() {
        return new SensorReadings(new float[0], new float[0], null, new float[0], List.of(), true, true, true);
    }
}
