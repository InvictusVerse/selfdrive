package com.selfdriving.world;

/**
 * Point on the ground plane.
 *
 * @param x east, m
 * @param y north, m
 */
public record Point2(double x, double y) {

    public double distanceTo(Point2 other) {
        return Math.hypot(other.x - x, other.y - y);
    }
}
