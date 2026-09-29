package com.selfdriving.sensors;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.world.Obstacle;
import com.selfdriving.world.Walls;

/**
 * Casts rays against obstacles and building walls. Every simulated sensor is built on this: a
 * ray starts at the sensor, and the nearest thing it meets is what the sensor "sees".
 *
 * <p>Before a scan, {@link #candidates} keeps only obstacles that could be within range; walls
 * come from a grid and are walked cell by cell, so a 360-degree lidar sweep stays cheap even in
 * a dense city.
 */
public final class RayCaster {

    /**
     * The nearest thing along a ray.
     *
     * @param obstacle   the obstacle hit, or null if it was a building wall
     * @param buildingId the building hit, or -1
     */
    public record Hit(double distance, Obstacle obstacle, int buildingId) {

        /** Id reported by sensors: the obstacle's, or the building's. */
        public int id() {
            return obstacle != null ? obstacle.id() : buildingId;
        }
    }

    private RayCaster() {
    }

    /** Obstacles whose bounding circle comes within {@code range} of (x, y). */
    public static List<Obstacle> candidates(double x, double y, double range, List<Obstacle> obstacles) {
        List<Obstacle> result = new ArrayList<>();
        for (Obstacle o : obstacles) {
            double limit = range + o.box().boundingRadius();
            double dx = o.box().cx() - x;
            double dy = o.box().cy() - y;
            if (dx * dx + dy * dy <= limit * limit) {
                result.add(o);
            }
        }
        return result;
    }

    /**
     * @param dx    unit direction east
     * @param dy    unit direction north
     * @param walls building walls, or null
     * @return the nearest hit, or null if nothing is within range
     */
    public static Hit cast(double ox, double oy, double dx, double dy, double range, List<Obstacle> candidates,
                           Walls walls) {
        double best = range;
        Obstacle hit = null;
        for (Obstacle o : candidates) {
            double d = o.box().rayDistance(ox, oy, dx, dy, best);
            if (d < best) {
                best = d;
                hit = o;
            }
        }
        if (walls != null) {
            Walls.Hit wall = walls.cast(ox, oy, dx, dy, best);
            if (wall != null && wall.distance() < best) {
                return new Hit(wall.distance(), null, wall.buildingId());
            }
        }
        return hit == null ? null : new Hit(best, hit, -1);
    }

    /** Obstacles only (no walls). */
    public static Hit cast(double ox, double oy, double dx, double dy, double range, List<Obstacle> candidates) {
        return cast(ox, oy, dx, dy, range, candidates, null);
    }
}
