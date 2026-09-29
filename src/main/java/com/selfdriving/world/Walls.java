package com.selfdriving.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The outer walls of all buildings as line segments in a uniform grid, so sensors and collisions
 * only look at walls near them. Footprints may be any shape (not only rectangles).
 *
 * <p>Rays walk the grid cell by cell (Amanatides-Woo traversal) and stop at the first wall, so a
 * 720-ray lidar sweep in a dense city stays cheap. Immutable and thread-safe.
 */
public final class Walls {

    private static final double CELL = 16;
    private static final double WALL_HALF_THICKNESS = 0.2;

    /** A wall between two corners. */
    private record Segment(double x0, double y0, double x1, double y1, int buildingId) {
    }

    /** The nearest wall along a ray. */
    public record Hit(double distance, int buildingId) {
    }

    private final Map<Long, List<Segment>> cells = new HashMap<>();

    public Walls(List<Building> buildings) {
        for (Building b : buildings) {
            List<Point2> f = b.footprint();
            for (int i = 0; i < f.size(); i++) {
                Point2 a = f.get(i);
                Point2 c = f.get((i + 1) % f.size());
                add(new Segment(a.x(), a.y(), c.x(), c.y(), b.id()));
            }
        }
    }

    private void add(Segment s) {
        int x0 = cell(Math.min(s.x0(), s.x1()));
        int x1 = cell(Math.max(s.x0(), s.x1()));
        int y0 = cell(Math.min(s.y0(), s.y1()));
        int y1 = cell(Math.max(s.y0(), s.y1()));
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                cells.computeIfAbsent(key(x, y), k -> new ArrayList<>()).add(s);
            }
        }
    }

    private static int cell(double v) {
        return (int) Math.floor(v / CELL);
    }

    private static long key(int x, int y) {
        return ((long) x << 32) ^ (y & 0xffffffffL);
    }

    /**
     * Casts a ray.
     *
     * @param dx unit direction east
     * @param dy unit direction north
     * @return the nearest wall within range, or null
     */
    public Hit cast(double ox, double oy, double dx, double dy, double range) {
        int cx = cell(ox);
        int cy = cell(oy);
        int stepX = dx > 0 ? 1 : -1;
        int stepY = dy > 0 ? 1 : -1;
        double tDeltaX = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : CELL / Math.abs(dx);
        double tDeltaY = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : CELL / Math.abs(dy);
        double nextX = (dx > 0 ? (cx + 1) * CELL - ox : ox - cx * CELL);
        double nextY = (dy > 0 ? (cy + 1) * CELL - oy : oy - cy * CELL);
        double tMaxX = Math.abs(dx) < 1e-12 ? Double.POSITIVE_INFINITY : nextX / Math.abs(dx);
        double tMaxY = Math.abs(dy) < 1e-12 ? Double.POSITIVE_INFINITY : nextY / Math.abs(dy);
        double best = range;
        int hit = -1;
        double t = 0;
        while (t <= best) {
            List<Segment> list = cells.get(key(cx, cy));
            if (list != null) {
                for (Segment s : list) {
                    double d = rayDistance(ox, oy, dx, dy, s);
                    if (d < best) {
                        best = d;
                        hit = s.buildingId();
                    }
                }
            }
            if (tMaxX < tMaxY) {
                t = tMaxX;
                tMaxX += tDeltaX;
                cx += stepX;
            } else {
                t = tMaxY;
                tMaxY += tDeltaY;
                cy += stepY;
            }
        }
        return hit < 0 ? null : new Hit(best, hit);
    }

    private static double rayDistance(double ox, double oy, double dx, double dy, Segment s) {
        double ex = s.x1() - s.x0();
        double ey = s.y1() - s.y0();
        double denominator = dx * ey - dy * ex;
        if (Math.abs(denominator) < 1e-12) {
            return Double.POSITIVE_INFINITY;
        }
        double qx = s.x0() - ox;
        double qy = s.y0() - oy;
        double t = (qx * ey - qy * ex) / denominator;
        double u = (qx * dy - qy * dx) / denominator;
        return t >= 0 && u >= 0 && u <= 1 ? t : Double.POSITIVE_INFINITY;
    }

    /** Ids of buildings with a wall closer than {@code radius} to a point. */
    public Set<Integer> buildingsNear(double x, double y, double radius) {
        Set<Integer> result = new HashSet<>();
        for (int cx = cell(x - radius); cx <= cell(x + radius); cx++) {
            for (int cy = cell(y - radius); cy <= cell(y + radius); cy++) {
                List<Segment> list = cells.get(key(cx, cy));
                if (list == null) {
                    continue;
                }
                for (Segment s : list) {
                    double sx = s.x1() - s.x0();
                    double sy = s.y1() - s.y0();
                    double len2 = sx * sx + sy * sy;
                    double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((x - s.x0()) * sx + (y - s.y0()) * sy) / len2));
                    if (Math.hypot(x - s.x0() - sx * t, y - s.y0() - sy * t) < radius) {
                        result.add(s.buildingId());
                    }
                }
            }
        }
        return result;
    }

    /**
     * How to push a box out of the walls it touches, or null. Uses the separating axis test
     * between the box and each wall segment and returns the deepest contact.
     */
    public OrientedBox.Contact contact(OrientedBox box, int[] buildingIdOut) {
        double r = box.boundingRadius();
        int x0 = cell(box.cx() - r);
        int x1 = cell(box.cx() + r);
        int y0 = cell(box.cy() - r);
        int y1 = cell(box.cy() + r);
        Set<Segment> seen = new HashSet<>();
        OrientedBox.Contact deepest = null;
        double c = Math.cos(box.heading());
        double s = Math.sin(box.heading());
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                List<Segment> list = cells.get(key(x, y));
                if (list == null) {
                    continue;
                }
                for (Segment seg : list) {
                    if (!seen.add(seg)) {
                        continue;
                    }
                    OrientedBox.Contact contact = boxSegment(box, c, s, seg);
                    if (contact != null && (deepest == null || contact.depth() > deepest.depth())) {
                        deepest = contact;
                        if (buildingIdOut != null) {
                            buildingIdOut[0] = seg.buildingId();
                        }
                    }
                }
            }
        }
        return deepest;
    }

    private static OrientedBox.Contact boxSegment(OrientedBox box, double c, double s, Segment seg) {
        double ex = seg.x1() - seg.x0();
        double ey = seg.y1() - seg.y0();
        double len = Math.hypot(ex, ey);
        if (len < 1e-9) {
            return null;
        }
        double nx = -ey / len;
        double ny = ex / len;
        double[][] axes = {{c, s}, {-s, c}, {nx, ny}};
        double bestDepth = Double.MAX_VALUE;
        double bestNx = 0;
        double bestNy = 0;
        for (double[] axis : axes) {
            double ax = axis[0];
            double ay = axis[1];
            double centre = box.cx() * ax + box.cy() * ay;
            double extent = Math.abs(c * ax + s * ay) * box.halfLength() + Math.abs(-s * ax + c * ay) * box.halfWidth();
            double p0 = seg.x0() * ax + seg.y0() * ay;
            double p1 = seg.x1() * ax + seg.y1() * ay;
            // The wall is a thin slab, so it has an inside even though it is drawn as a line.
            double thickness = WALL_HALF_THICKNESS * Math.abs(nx * ax + ny * ay);
            double segMin = Math.min(p0, p1) - thickness;
            double segMax = Math.max(p0, p1) + thickness;
            double overlap = Math.min(centre + extent, segMax) - Math.max(centre - extent, segMin);
            if (overlap <= 0) {
                return null;
            }
            if (overlap < bestDepth) {
                bestDepth = overlap;
                double segCentre = (segMin + segMax) / 2;
                double sign = centre >= segCentre ? 1 : -1;
                bestNx = ax * sign;
                bestNy = ay * sign;
            }
        }
        return new OrientedBox.Contact(bestNx, bestNy, bestDepth);
    }
}
