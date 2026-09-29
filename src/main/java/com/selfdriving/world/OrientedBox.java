package com.selfdriving.world;

/**
 * A rectangle on the ground plane that can be rotated: the shape of the car, other cars,
 * pedestrians, barriers and buildings. Sensors ray-cast against it and the collision system
 * tests boxes against each other (separating axis theorem).
 *
 * @param cx         centre east, m
 * @param cy         centre north, m
 * @param heading    rotation, rad, counter-clockwise from east (the box's "length" axis)
 * @param halfLength half size along the heading, m
 * @param halfWidth  half size across the heading, m
 */
public record OrientedBox(double cx, double cy, double heading, double halfLength, double halfWidth) {

    /** Result of an overlap test: push {@code this} box along (nx, ny) by {@code depth}. */
    public record Contact(double nx, double ny, double depth) {
    }

    /** Axis-aligned box from its corners. */
    public static OrientedBox axisAligned(double minX, double minY, double maxX, double maxY) {
        return new OrientedBox((minX + maxX) / 2, (minY + maxY) / 2, 0, (maxX - minX) / 2, (maxY - minY) / 2);
    }

    /** Same size and heading at a new place. */
    public OrientedBox movedTo(double x, double y, double newHeading) {
        return new OrientedBox(x, y, newHeading, halfLength, halfWidth);
    }

    /** Radius of the circle that contains the box (for cheap distance checks). */
    public double boundingRadius() {
        return Math.hypot(halfLength, halfWidth);
    }

    /** The four corners, counter-clockwise, as {x0, y0, x1, y1, ...}. */
    public double[] corners() {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double lx = c * halfLength;
        double ly = s * halfLength;
        double wx = -s * halfWidth;
        double wy = c * halfWidth;
        return new double[] {
                cx + lx - wx, cy + ly - wy,
                cx + lx + wx, cy + ly + wy,
                cx - lx + wx, cy - ly + wy,
                cx - lx - wx, cy - ly - wy};
    }

    /** True if the point lies inside the box. */
    public boolean contains(double x, double y) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double dx = x - cx;
        double dy = y - cy;
        double along = dx * c + dy * s;
        double across = -dx * s + dy * c;
        return Math.abs(along) <= halfLength && Math.abs(across) <= halfWidth;
    }

    /**
     * Distance along a ray to where it first enters the box (slab method in the box's frame).
     *
     * @param ox       ray start east
     * @param oy       ray start north
     * @param dx       ray direction (unit vector) east
     * @param dy       ray direction north
     * @param maxRange longest distance of interest
     * @return distance, or {@link Double#POSITIVE_INFINITY} if the ray misses within range
     */
    public double rayDistance(double ox, double oy, double dx, double dy, double maxRange) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        double rx = ox - cx;
        double ry = oy - cy;
        // Ray origin and direction in box coordinates.
        double px = rx * c + ry * s;
        double py = -rx * s + ry * c;
        double qx = dx * c + dy * s;
        double qy = -dx * s + dy * c;

        double tMin = 0;
        double tMax = maxRange;
        double[] origin = {px, py};
        double[] dir = {qx, qy};
        double[] half = {halfLength, halfWidth};
        for (int axis = 0; axis < 2; axis++) {
            if (Math.abs(dir[axis]) < 1e-12) {
                if (Math.abs(origin[axis]) > half[axis]) {
                    return Double.POSITIVE_INFINITY;
                }
                continue;
            }
            double t1 = (-half[axis] - origin[axis]) / dir[axis];
            double t2 = (half[axis] - origin[axis]) / dir[axis];
            if (t1 > t2) {
                double tmp = t1;
                t1 = t2;
                t2 = tmp;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return tMin;
    }

    /**
     * Separating-axis overlap test.
     *
     * @return how to push this box out of the other one, or null if they do not touch
     */
    public Contact overlap(OrientedBox other) {
        double dx = cx - other.cx;
        double dy = cy - other.cy;
        double limit = boundingRadius() + other.boundingRadius();
        if (dx * dx + dy * dy > limit * limit) {
            return null;
        }
        double[] axes = {
                Math.cos(heading), Math.sin(heading),
                -Math.sin(heading), Math.cos(heading),
                Math.cos(other.heading), Math.sin(other.heading),
                -Math.sin(other.heading), Math.cos(other.heading)};
        double bestDepth = Double.MAX_VALUE;
        double bestX = 0;
        double bestY = 0;
        for (int i = 0; i < 4; i++) {
            double ax = axes[2 * i];
            double ay = axes[2 * i + 1];
            double distance = dx * ax + dy * ay;
            double depth = projectedRadius(ax, ay) + other.projectedRadius(ax, ay) - Math.abs(distance);
            if (depth <= 0) {
                return null;
            }
            if (depth < bestDepth) {
                bestDepth = depth;
                double sign = distance >= 0 ? 1 : -1;
                bestX = ax * sign;
                bestY = ay * sign;
            }
        }
        return new Contact(bestX, bestY, bestDepth);
    }

    private double projectedRadius(double ax, double ay) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        return halfLength * Math.abs(c * ax + s * ay) + halfWidth * Math.abs(-s * ax + c * ay);
    }
}
