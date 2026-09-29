package com.selfdriving.world;

import java.util.List;

/**
 * A building: its footprint on the ground (counter-clockwise) and height.
 *
 * @param id        unique id (from 1000, as reported by sensors)
 * @param footprint outline, counter-clockwise, not repeating the first point
 * @param height    m
 * @param name      name from the map, or empty
 */
public record Building(int id, List<Point2> footprint, double height, String name) {

    public Building {
        footprint = List.copyOf(footprint);
    }

    /** Signed area: positive when the outline runs counter-clockwise, m^2. */
    public static double signedArea(List<Point2> outline) {
        double sum = 0;
        for (int i = 0; i < outline.size(); i++) {
            Point2 a = outline.get(i);
            Point2 b = outline.get((i + 1) % outline.size());
            sum += a.x() * b.y() - b.x() * a.y();
        }
        return sum / 2;
    }

    /** Centre of the outline's points. */
    public Point2 centre() {
        double x = 0;
        double y = 0;
        for (Point2 p : footprint) {
            x += p.x();
            y += p.y();
        }
        return new Point2(x / footprint.size(), y / footprint.size());
    }
}
