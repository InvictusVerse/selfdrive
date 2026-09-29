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
            Point2 prev = i > 0 ? points.get(i - 1) : closed ? points.get(n - 1) : points.get(i);
            Point2 next = i < n - 1 ? points.get(i + 1) : closed ? points.get(0) : points.get(i);
            double tx = next.x() - prev.x();
            double ty = next.y() - prev.y();
            double len = Math.hypot(tx, ty);
            if (len < 1e-9) {
                shifted.add(points.get(i));
                continue;
            }
            double nx = -ty / len;
            double ny = tx / len;
            Point2 p = points.get(i);
            shifted.add(new Point2(p.x() + nx * distance, p.y() + ny * distance));
        }
        return new Polyline(shifted, closed);
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
