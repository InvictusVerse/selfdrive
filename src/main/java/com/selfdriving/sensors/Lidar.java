package com.selfdriving.sensors;

import java.util.List;

import com.selfdriving.world.Obstacle;

/**
 * Roof-mounted 360-degree lidar: one ray every 0.5 degrees, 100 m range, small range noise.
 * At 40 m the rays are 35 cm apart, so even a pedestrian is always hit.
 */
public final class Lidar {

    public static final int RAYS = 720;
    public static final double RANGE = 100;
    private static final double NOISE = 0.03;

    private final float[] angles = new float[RAYS];
    private final java.util.Random noise = new java.util.Random(7);

    public Lidar() {
        for (int i = 0; i < RAYS; i++) {
            angles[i] = (float) (2 * Math.PI * i / RAYS);
        }
    }

    /** Relative ray directions (a copy). */
    public float[] angles() {
        return angles.clone();
    }

    /**
     * Sweeps all rays from the car.
     *
     * @param hitIds receives the id of the obstacle each ray hit (-1 for none)
     * @return range per ray (NaN = nothing within range)
     */
    public float[] scan(double x, double y, double heading, List<Obstacle> obstacles, int[] hitIds) {
        List<Obstacle> candidates = RayCaster.candidates(x, y, RANGE, obstacles);
        float[] ranges = new float[RAYS];
        for (int i = 0; i < RAYS; i++) {
            double a = heading + angles[i];
            RayCaster.Hit hit = RayCaster.cast(x, y, Math.cos(a), Math.sin(a), RANGE, candidates);
            if (hit == null) {
                ranges[i] = Float.NaN;
                hitIds[i] = -1;
            } else {
                ranges[i] = (float) Math.max(0, hit.distance() + noise.nextGaussian() * NOISE);
                hitIds[i] = hit.obstacle().id();
            }
        }
        return ranges;
    }
}
