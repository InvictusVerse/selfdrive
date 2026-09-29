package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;

/**
 * A car park beside the proving ground's access road, on the left as traffic keeps left:
 * a row of parallel spaces with cars parked in all but one, and a row of bays for reversing in,
 * some taken. It is the place to try automatic parking; the Park button also finds spaces along
 * any city kerb.
 */
public final class ParkingArea {

    /**
     * A parking bay, perpendicular to the road.
     *
     * @param centre  centre of the bay
     * @param heading direction a car faces when reversed in (nose towards the road), rad
     */
    public record Bay(Point2 centre, double heading, double width, double depth) {
    }

    /** Road the car park lies along: x = 0, driven north; left edge at x = -3.5. */
    private static final double ROAD_EDGE = -3.5;
    /** Parallel spaces: centre line 1.1 m outside the road edge. */
    private static final double KERB_ROW = ROAD_EDGE - 1.1;
    private static final double SPACE = 6.5;
    private static final double BAY_WIDTH = 2.7;
    private static final double BAY_DEPTH = 5.5;

    private final List<Obstacle> parked = new ArrayList<>();
    private final List<Marking> markings = new ArrayList<>();
    private final List<Bay> bays = new ArrayList<>();

    public ParkingArea() {
        // Parallel spaces from y = 14: cars in all but the fourth.
        int id = 500;
        for (int i = 0; i < 6; i++) {
            double y0 = 14 + i * SPACE;
            line(KERB_ROW - 1.1, y0, KERB_ROW + 1.0, y0);
            if (i != 3) {
                parked.add(new Obstacle(id++, Obstacle.Kind.CAR,
                        new OrientedBox(KERB_ROW, y0 + SPACE / 2, Math.PI / 2, 2.25, 0.9), 1.5, "Parked car"));
            }
        }
        line(KERB_ROW - 1.1, 14 + 6 * SPACE, KERB_ROW + 1.0, 14 + 6 * SPACE);
        line(KERB_ROW - 1.1, 14, KERB_ROW - 1.1, 14 + 6 * SPACE);

        // Bays from y = 62: taken, free, taken, free, taken.
        double bayCentreX = ROAD_EDGE - 0.2 - BAY_DEPTH / 2;
        for (int i = 0; i < 5; i++) {
            double y0 = 62 + i * BAY_WIDTH;
            line(ROAD_EDGE - 0.2, y0, ROAD_EDGE - 0.2 - BAY_DEPTH, y0);
            double cy = y0 + BAY_WIDTH / 2;
            if (i % 2 == 0) {
                parked.add(new Obstacle(id++, Obstacle.Kind.CAR,
                        new OrientedBox(bayCentreX, cy, 0, 2.25, 0.9), 1.5, "Parked car"));
            }
            bays.add(new Bay(new Point2(bayCentreX, cy), 0, BAY_WIDTH, BAY_DEPTH));
        }
        line(ROAD_EDGE - 0.2, 62 + 5 * BAY_WIDTH, ROAD_EDGE - 0.2 - BAY_DEPTH, 62 + 5 * BAY_WIDTH);
        line(ROAD_EDGE - 0.2 - BAY_DEPTH, 62, ROAD_EDGE - 0.2 - BAY_DEPTH, 62 + 5 * BAY_WIDTH);
    }

    private void line(double x0, double y0, double x1, double y1) {
        markings.add(new Marking(Polyline.of(false, new Point2(x0, y0), new Point2(x1, y1)), 0.12, Marking.Kind.EDGE));
    }

    /** Cars already parked (static obstacles). */
    public List<Obstacle> parkedCars() {
        return List.copyOf(parked);
    }

    public List<Marking> markings() {
        return List.copyOf(markings);
    }

    public List<Bay> bays() {
        return List.copyOf(bays);
    }

    /** Where to try parking: the access road just south of the spaces. */
    public Pose approach() {
        return new Pose(-1.75, 10, Math.PI / 2);
    }
}
