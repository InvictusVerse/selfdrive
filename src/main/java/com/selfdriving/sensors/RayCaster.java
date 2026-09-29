package com.selfdriving.sensors;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.world.Obstacle;

/**
 * Casts rays against obstacles. Every simulated sensor is built on this: a ray starts at the
 * sensor, and the nearest obstacle it enters is what the sensor "sees".
 *
 * <p>Before a scan, {@link #candidates} keeps only obstacles that could be within range, so a
 * 360-degree lidar sweep stays cheap even with many buildings.
 */
public final class RayCaster {

    /** The nearest obstacle along a ray. */
    public record Hit(double distance, Obstacle obstacle) {
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
     * @param dx unit direction east
     * @param dy unit direction north
     * @return the nearest hit, or null if nothing is within range
     */
    public static Hit cast(double ox, double oy, double dx, double dy, double range, List<Obstacle> candidates) {
        double best = range;
        Obstacle hit = null;
        for (Obstacle o : candidates) {
            double d = o.box().rayDistance(ox, oy, dx, dy, best);
            if (d < best) {
                best = d;
                hit = o;
            }
        }
        return hit == null ? null : new Hit(best, hit);
    }
}
