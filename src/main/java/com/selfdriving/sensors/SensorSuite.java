package com.selfdriving.sensors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.selfdriving.world.Obstacle;
import com.selfdriving.world.Walls;

/**
 * All of the car's sensors, and a simple perception step that turns their raw returns into
 * a list of objects around the car.
 *
 * <ul>
 *   <li><b>Lidar</b> on the roof, 360 degrees, 100 m ({@link Lidar}),</li>
 *   <li><b>radar</b> in the front bumper, 160 m ({@link Radar}),</li>
 *   <li><b>ultrasonic</b> parking sensors, eight around the bumpers, 5 m.</li>
 * </ul>
 * Any sensor can be switched off to simulate a failure; the rest keep working.
 */
public final class SensorSuite {

    public static final String[] ULTRASONIC_NAMES = {
            "Front left", "Front centre-left", "Front centre-right", "Front right",
            "Rear left", "Rear centre-left", "Rear centre-right", "Rear right"};

    public static final double ULTRASONIC_RANGE = 5;

    /** Sensor positions relative to the car's centre: {forward, left, direction offset rad}. */
    private static final double[][] ULTRASONIC_MOUNTS = {
            {2.25, 0.75, Math.toRadians(45)}, {2.38, 0.30, 0}, {2.38, -0.30, 0}, {2.25, -0.75, Math.toRadians(-45)},
            {-2.20, 0.75, Math.toRadians(135)}, {-2.30, 0.30, Math.PI}, {-2.30, -0.30, Math.PI},
            {-2.20, -0.75, Math.toRadians(-135)}};

    private static final double RADAR_MOUNT = 2.38;
    private static final double POSITION_NOISE = 0.05;

    private final Lidar lidar = new Lidar();
    private final Radar radar = new Radar();
    private final float[] lidarAngles = lidar.angles();
    private final Random noise = new Random(11);
    private boolean lidarWorking = true;
    private boolean radarWorking = true;
    private boolean ultrasonicWorking = true;

    /**
     * Runs every sensor from the car's current pose.
     *
     * @param x         car centre east
     * @param y         car centre north
     * @param heading   car heading, rad
     * @param vx        car velocity east (world), m/s
     * @param vy        car velocity north (world), m/s
     * @param obstacles moving and temporary objects
     * @param walls     building walls (or null)
     */
    public SensorReadings scan(double x, double y, double heading, double vx, double vy, List<Obstacle> obstacles,
                               Walls walls) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);

        Set<Integer> seen = new LinkedHashSet<>();
        float[] ranges;
        if (lidarWorking) {
            int[] hits = new int[Lidar.RAYS];
            ranges = lidar.scan(x, y, heading, obstacles, walls, hits);
            for (int id : hits) {
                if (id >= 0) {
                    seen.add(id);
                }
            }
        } else {
            ranges = new float[Lidar.RAYS];
            java.util.Arrays.fill(ranges, Float.NaN);
        }

        SensorReadings.RadarTarget target = null;
        if (radarWorking) {
            target = radar.scan(x + c * RADAR_MOUNT, y + s * RADAR_MOUNT, heading, vx, vy, obstacles, walls);
            if (target != null) {
                seen.add(target.obstacleId());
            }
        }

        float[] ultrasonic = new float[ULTRASONIC_MOUNTS.length];
        List<Obstacle> near = RayCaster.candidates(x, y, ULTRASONIC_RANGE + 3, obstacles);
        for (int i = 0; i < ULTRASONIC_MOUNTS.length; i++) {
            if (!ultrasonicWorking) {
                ultrasonic[i] = Float.NaN;
                continue;
            }
            double[] m = ULTRASONIC_MOUNTS[i];
            double ox = x + c * m[0] - s * m[1];
            double oy = y + s * m[0] + c * m[1];
            double a = heading + m[2];
            RayCaster.Hit hit = RayCaster.cast(ox, oy, Math.cos(a), Math.sin(a), ULTRASONIC_RANGE, near, walls);
            ultrasonic[i] = hit == null ? Float.NaN : (float) hit.distance();
        }

        return new SensorReadings(lidarAngles.clone(), ranges, target, ultrasonic,
                perceive(seen, obstacles, x, y), lidarWorking, radarWorking, ultrasonicWorking);
    }

    /** Objects the sensors saw, with measured (slightly noisy) positions. Buildings are map data. */
    private List<SensorReadings.DetectedObject> perceive(Set<Integer> seen, List<Obstacle> obstacles, double x,
                                                         double y) {
        Map<Integer, Obstacle> byId = new HashMap<>();
        for (Obstacle o : obstacles) {
            byId.put(o.id(), o);
        }
        List<SensorReadings.DetectedObject> result = new ArrayList<>();
        for (int id : seen) {
            Obstacle o = byId.get(id);
            if (o == null || o.kind() == Obstacle.Kind.BUILDING) {
                continue;
            }
            double ox = o.box().cx() + noise.nextGaussian() * POSITION_NOISE;
            double oy = o.box().cy() + noise.nextGaussian() * POSITION_NOISE;
            result.add(new SensorReadings.DetectedObject(o.id(), o.kind(), ox, oy, o.box().heading(),
                    o.box().halfLength(), o.box().halfWidth(), o.vx(), o.vy(), Math.hypot(ox - x, oy - y)));
        }
        return result;
    }

    public boolean isLidarWorking() {
        return lidarWorking;
    }

    public void setLidarWorking(boolean working) {
        lidarWorking = working;
    }

    public boolean isRadarWorking() {
        return radarWorking;
    }

    public void setRadarWorking(boolean working) {
        radarWorking = working;
    }

    public boolean isUltrasonicWorking() {
        return ultrasonicWorking;
    }

    public void setUltrasonicWorking(boolean working) {
        ultrasonicWorking = working;
    }
}
