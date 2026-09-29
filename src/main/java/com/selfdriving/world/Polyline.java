package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;

/**
 * A line through the ground plane made of straight pieces; optionally closed into a loop.
 * Roads, lane lines and markings are all polylines.
 */
public final class Polyline {

    private final List<Point2> points;
    private final boolean closed;

    public Polyline(List<Point2> points, boolean closed) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("A polyline needs at least two points");
        }
        this.points = List.copyOf(points);
        this.closed = closed;
    }

    public static Polyline of(boolean closed, Point2... points) {
        return new Polyline(List.of(points), closed);
    }

    public List<Point2> points() {
        return points;
    }

    public boolean closed() {
        return closed;
    }

    /** Total length, m. */
    public double length() {
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i - 1).distanceTo(points.get(i));
        }
        if (closed) {
            total += points.get(points.size() - 1).distanceTo(points.get(0));
        }
        return total;
    }

    /**
     * The same line shifted sideways: positive distance to the left of the direction of travel.
     * Each point moves along the average normal of its neighbouring segments.
     */
    public Polyline offset(double distance) {
        List<Point2> shifted = new ArrayList<>(points.size());
        int n = points.size();
        for (int i = 0; i < n; i++) {
            Point2 p = points.get(i);
            Point2 prev = i > 0 ? points.get(i - 1) : closed ? points.get(n - 1) : null;
            Point2 next = i < n - 1 ? points.get(i + 1) : closed ? points.get(0) : null;
            double[] n1 = prev == null ? null : normal(prev, p);
            double[] n2 = next == null ? null : normal(p, next);
            double nx;
            double ny;
            if (n1 == null && n2 == null) {
                shifted.add(p);
                continue;
            } else if (n1 == null) {
                nx = n2[0];
                ny = n2[1];
            } else if (n2 == null) {
                nx = n1[0];
                ny = n1[1];
            } else {
                // Mitre join: along the bisector, lengthened so both neighbouring segments keep the
                // full offset (limited at very sharp corners).
                double bx = n1[0] + n2[0];
                double by = n1[1] + n2[1];
                double len = Math.hypot(bx, by);
                if (len < 1e-6) {
                    nx = n1[0];
                    ny = n1[1];
                } else {
                    bx /= len;
                    by /= len;
                    double cos = Math.max(0.4, bx * n1[0] + by * n1[1]);
                    nx = bx / cos;
                    ny = by / cos;
                }
            }
            shifted.add(new Point2(p.x() + nx * distance, p.y() + ny * distance));
        }
        return new Polyline(shifted, closed);
    }

    /** Unit normal to the left of a→b, or null for a zero-length segment. */
    private static double[] normal(Point2 a, Point2 b) {
        double tx = b.x() - a.x();
        double ty = b.y() - a.y();
        double len = Math.hypot(tx, ty);
        return len < 1e-9 ? null : new double[] {-ty / len, tx / len};
    }

    // ---- Distance along the line (open lines) ---------------------------------------------

    private double[] arcs;

    /** Cumulative distance at each point, m. */
    private double[] arcs() {
        if (arcs == null) {
            double[] a = new double[points.size()];
            for (int i = 1; i < a.length; i++) {
                a[i] = a[i - 1] + points.get(i - 1).distanceTo(points.get(i));
            }
            arcs = a;
        }
        return arcs;
    }

    private int segmentAt(double arc) {
        double[] a = arcs();
        int lo = 0;
        int hi = a.length - 2;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (a[mid] <= arc) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return Math.max(0, lo);
    }

    /** Point at a distance from the start (clamped to the line). */
    public Point2 pointAt(double arc) {
        double[] a = arcs();
        if (arc <= 0) {
            return points.get(0);
        }
        if (arc >= a[a.length - 1]) {
            return points.get(points.size() - 1);
        }
        int i = segmentAt(arc);
        double len = a[i + 1] - a[i];
        double t = len < 1e-9 ? 0 : (arc - a[i]) / len;
        Point2 p = points.get(i);
        Point2 q = points.get(i + 1);
        return new Point2(p.x() + (q.x() - p.x()) * t, p.y() + (q.y() - p.y()) * t);
    }

    /** Direction of travel at a distance from the start, rad. */
    public double headingAt(double arc) {
        int i = Math.min(segmentAt(Math.max(0, arc)), points.size() - 2);
        Point2 p = points.get(i);
        Point2 q = points.get(i + 1);
        if (p.distanceTo(q) < 1e-9 && i + 2 < points.size()) {
            q = points.get(i + 2);
        }
        return Math.atan2(q.y() - p.y(), q.x() - p.x());
    }

    /** The part of an open line between two distances from its start. */
    public Polyline slice(double from, double to) {
        double[] a = arcs();
        double total = a[a.length - 1];
        from = Math.max(0, Math.min(total, from));
        to = Math.max(from, Math.min(total, to));
        List<Point2> result = new ArrayList<>();
        result.add(pointAt(from));
        for (int i = 1; i < points.size() - 1; i++) {
            if (a[i] > from + 1e-6 && a[i] < to - 1e-6) {
                result.add(points.get(i));
            }
        }
        Point2 end = pointAt(to);
        if (end.distanceTo(result.get(result.size() - 1)) < 1e-6) {
            // keep two distinct points for very short slices
            double h = headingAt(from);
            end = new Point2(result.get(0).x() + Math.cos(h) * 0.01, result.get(0).y() + Math.sin(h) * 0.01);
        }
        result.add(end);
        return new Polyline(result, false);
    }

    /**
     * Rounds sharp corners (Chaikin corner cutting), keeping the end points. Map data draws
     * roads with few points and hard corners; real kerbs are curves.
     *
     * @param maxCut the most taken off each side of a corner, m
     */
    public Polyline smoothed(int iterations, double maxCut) {
        List<Point2> current = points;
        for (int it = 0; it < iterations && current.size() > 2; it++) {
            List<Point2> next = new ArrayList<>(current.size() * 2);
            next.add(current.get(0));
            for (int i = 0; i < current.size() - 1; i++) {
                Point2 a = current.get(i);
                Point2 b = current.get(i + 1);
                double len = a.distanceTo(b);
                double cut = len < 1e-9 ? 0 : Math.min(0.25, maxCut / len);
                if (i > 0) {
                    next.add(new Point2(a.x() + (b.x() - a.x()) * cut, a.y() + (b.y() - a.y()) * cut));
                }
                if (i < current.size() - 2) {
                    next.add(new Point2(b.x() - (b.x() - a.x()) * cut, b.y() - (b.y() - a.y()) * cut));
                }
            }
            next.add(current.get(current.size() - 1));
            current = next;
        }
        return new Polyline(current, closed);
    }

    /**
     * Splits the line into dashes.
     *
     * @param dash length of each dash, m
     * @param gap  length of each gap, m
     */
    public List<Polyline> dashes(double dash, double gap) {
        List<Point2> path = new ArrayList<>(points);
        if (closed) {
            path.add(points.get(0));
        }
        List<Polyline> result = new ArrayList<>();
        List<Point2> current = new ArrayList<>();
        boolean drawing = true;
        double remaining = dash;
        current.add(path.get(0));
        for (int i = 1; i < path.size(); i++) {
            Point2 a = path.get(i - 1);
            Point2 b = path.get(i);
            double segment = a.distanceTo(b);
            double used = 0;
            while (segment - used > remaining) {
                used += remaining;
                double t = used / segment;
                Point2 cut = new Point2(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
                if (drawing) {
                    current.add(cut);
                    result.add(new Polyline(current, false));
                    current = new ArrayList<>();
                } else {
                    current.add(cut);
                }
                drawing = !drawing;
                remaining = drawing ? dash : gap;
            }
            remaining -= segment - used;
            if (drawing) {
                current.add(b);
            }
        }
        if (drawing && current.size() >= 2) {
            result.add(new Polyline(current, false));
        }
        return result;
    }

    /**
     * Closest point on the line to (x, y).
     *
     * @param arc      distance along the line from its first point, m
     * @param distance distance from (x, y) to the line, m
     * @param heading  direction of the line at that point, rad
     */
    public record Projection(double arc, double distance, double heading) {
    }

    /** Projects a point onto the line (open lines only use their actual segments). */
    public Projection project(double x, double y) {
        List<Point2> path = new ArrayList<>(points);
        if (closed) {
            path.add(points.get(0));
        }
        double bestDistance = Double.MAX_VALUE;
        double bestArc = 0;
        double bestHeading = 0;
        double travelled = 0;
        for (int i = 1; i < path.size(); i++) {
            Point2 a = path.get(i - 1);
            Point2 b = path.get(i);
            double sx = b.x() - a.x();
            double sy = b.y() - a.y();
            double len2 = sx * sx + sy * sy;
            double len = Math.sqrt(len2);
            double t = len2 < 1e-12 ? 0 : ((x - a.x()) * sx + (y - a.y()) * sy) / len2;
            t = Math.max(0, Math.min(1, t));
            double px = a.x() + sx * t;
            double py = a.y() + sy * t;
            double d = Math.hypot(x - px, y - py);
            if (d < bestDistance) {
                bestDistance = d;
                bestArc = travelled + t * len;
                bestHeading = Math.atan2(sy, sx);
            }
            travelled += len;
        }
        return new Projection(bestArc, bestDistance, bestHeading);
    }

    /** The same line in the opposite direction. */
    public Polyline reversed() {
        List<Point2> copy = new ArrayList<>(points);
        java.util.Collections.reverse(copy);
        return new Polyline(copy, closed);
    }

    // ---- Builders -----------------------------------------------------------------------

    /** Points along a circular arc, spaced about {@code step} metres apart. */
    public static List<Point2> arc(double cx, double cy, double radius, double fromAngle, double toAngle,
                                   double step) {
        int count = Math.max(2, (int) Math.ceil(Math.abs(toAngle - fromAngle) * radius / step) + 1);
        List<Point2> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double a = fromAngle + (toAngle - fromAngle) * i / (count - 1);
            result.add(new Point2(cx + radius * Math.cos(a), cy + radius * Math.sin(a)));
        }
        return result;
    }

    /** Points along a straight line, spaced about {@code step} metres apart. */
    public static List<Point2> straight(Point2 from, Point2 to, double step) {
        int count = Math.max(2, (int) Math.ceil(from.distanceTo(to) / step) + 1);
        List<Point2> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double t = (double) i / (count - 1);
            result.add(new Point2(from.x() + (to.x() - from.x()) * t, from.y() + (to.y() - from.y()) * t));
        }
        return result;
    }
}
