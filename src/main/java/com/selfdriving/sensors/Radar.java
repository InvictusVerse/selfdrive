package com.selfdriving.sensors;

import java.util.List;

import com.selfdriving.world.Obstacle;
import com.selfdriving.world.Walls;

/**
 * Forward radar in the front bumper: a narrow cone (plus or minus 8 degrees) out to 160 m.
 * Reports the nearest object and how fast the gap to it is closing, which is exactly what
 * adaptive cruise control and emergency braking need.
 */
public final class Radar {

    public static final double RANGE = 160;
    public static final double HALF_ANGLE = Math.toRadians(8);
    private static final int RAYS = 33;

    /**
     * @param x         sensor position east (front bumper)
     * @param y         sensor position north
     * @param heading   car heading, rad
     * @param carVx     car velocity east, m/s
     * @param carVy     car velocity north, m/s
     * @param obstacles objects to look for
     */
    public SensorReadings.RadarTarget scan(double x, double y, double heading, double carVx, double carVy,
                                           List<Obstacle> obstacles, Walls walls) {
        List<Obstacle> candidates = RayCaster.candidates(x, y, RANGE, obstacles);
        RayCaster.Hit nearest = null;
        double nearestDx = 0;
        double nearestDy = 0;
        for (int i = 0; i < RAYS; i++) {
            double a = heading - HALF_ANGLE + 2 * HALF_ANGLE * i / (RAYS - 1);
            double dx = Math.cos(a);
            double dy = Math.sin(a);
            RayCaster.Hit hit = RayCaster.cast(x, y, dx, dy, RANGE, candidates, walls);
            if (hit == null || hit.obstacle() == null) {
                continue; // walls hide what is behind them; fixed roadside clutter is filtered out
            }
            if (nearest == null || hit.distance() < nearest.distance()) {
                nearest = hit;
                nearestDx = dx;
                nearestDy = dy;
            }
        }
        if (nearest == null) {
            return null;
        }
        // Doppler: relative velocity along the line of sight.
        double closing = (carVx - nearest.obstacle().vx()) * nearestDx + (carVy - nearest.obstacle().vy()) * nearestDy;
        return new SensorReadings.RadarTarget(nearest.distance(), closing, nearest.obstacle().id());
    }
}
