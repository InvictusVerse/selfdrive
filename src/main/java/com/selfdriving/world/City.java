package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * A city district north of the proving ground: a grid of two-way streets (one lane each way),
 * blocks of buildings of different heights, a few parks and named places. A connector road
 * links it to the circuit's top straight.
 *
 * <p>The layout is generated from the grid numbers and a fixed random seed, so it is the same
 * every run (repeatable tests and screenshots).
 */
public final class City {

    /** Street positions east-west, m. */
    public static final double[] COLUMNS = {-480, -360, -240, -120, 0, 120, 240, 360, 480};
    /** Street positions north-south, m. */
    public static final double[] ROWS = {420, 540, 660, 780};

    public static final double STREET_WIDTH = 8;
    public static final int STREET_LANES = 2;
    /** City speed limit, m/s (50 km/h). */
    public static final double STREET_SPEED_LIMIT = 50 / 3.6;
    /** Connector road speed limit, m/s (60 km/h). */
    public static final double CONNECTOR_SPEED_LIMIT = 60 / 3.6;

    private static final double INTERSECTION_CLEARANCE = 5.0;
    private static final double SIDEWALK = 4.0;
    private static final double LOT_GAP = 7.0;
    private static final double EDGE_LINE_WIDTH = 0.15;
    private static final double CENTRE_LINE_WIDTH = 0.14;
    private static final long LAYOUT_SEED = 20_260_929L;
    private static final int FIRST_BUILDING_ID = 1000;

    /** Blocks without buildings, as {column, row} of their south-west corner. */
    private static final int[][] PARKS = {{1, 1}, {6, 0}, {3, 2}};

    /** A street between two neighbouring junctions (two-way). */
    public record Street(Point2 from, Point2 to) {
    }

    private final List<Street> streets = new ArrayList<>();
    private final List<Road> roads = new ArrayList<>();
    private final List<Marking> markings = new ArrayList<>();
    private final List<Obstacle> buildings = new ArrayList<>();
    private final List<Place> places = new ArrayList<>();
    private final Street connector;

    public City(ProvingGround ground) {
        for (int c = 0; c < COLUMNS.length; c++) {
            for (int r = 0; r < ROWS.length; r++) {
                if (c + 1 < COLUMNS.length) {
                    streets.add(new Street(node(c, r), node(c + 1, r)));
                }
                if (r + 1 < ROWS.length) {
                    streets.add(new Street(node(c, r), node(c, r + 1)));
                }
            }
        }
        for (Street street : streets) {
            addStreet(street.from(), street.to(), "Street");
        }

        Point2 top = ground.topJunction();
        connector = new Street(top, node(4, 0));
        addStreet(new Point2(top.x(), top.y() + ground.circuitWidth() / 2), connector.to(), "Connector");

        addBuildings();

        places.add(new Place("Central Station", node(4, 3)));
        places.add(new Place("Tech Park", node(8, 3)));
        places.add(new Place("City Hospital", node(0, 2)));
        places.add(new Place("Market Square", node(2, 1)));
        places.add(new Place("Museum", node(6, 2)));
        places.add(new Place("Riverside", node(8, 0)));
        places.add(new Place("West Gate", node(0, 0)));
    }

    /** Junction position of grid column c, row r. */
    public static Point2 node(int column, int row) {
        return new Point2(COLUMNS[column], ROWS[row]);
    }

    private void addStreet(Point2 from, Point2 to, String name) {
        Polyline centre = new Polyline(Polyline.straight(from, to, 4), false);
        roads.add(new Road(name, centre, STREET_WIDTH, STREET_LANES));

        // Paint stops short of junctions so intersections stay clear.
        double length = from.distanceTo(to);
        double t0 = INTERSECTION_CLEARANCE / length;
        double t1 = 1 - t0;
        Point2 a = lerp(from, to, t0);
        Point2 b = lerp(from, to, t1);
        Polyline painted = new Polyline(Polyline.straight(a, b, 4), false);
        double inset = STREET_WIDTH / 2 - 0.3;
        markings.add(new Marking(painted.offset(inset), EDGE_LINE_WIDTH, Marking.Kind.EDGE));
        markings.add(new Marking(painted.offset(-inset), EDGE_LINE_WIDTH, Marking.Kind.EDGE));
        for (Polyline dash : painted.dashes(3, 5)) {
            markings.add(new Marking(dash, CENTRE_LINE_WIDTH, Marking.Kind.LANE));
        }
    }

    private void addBuildings() {
        Random random = new Random(LAYOUT_SEED);
        int id = FIRST_BUILDING_ID;
        for (int c = 0; c + 1 < COLUMNS.length; c++) {
            for (int r = 0; r + 1 < ROWS.length; r++) {
                if (isPark(c, r)) {
                    continue;
                }
                double minX = COLUMNS[c] + STREET_WIDTH / 2 + SIDEWALK;
                double maxX = COLUMNS[c + 1] - STREET_WIDTH / 2 - SIDEWALK;
                double minY = ROWS[r] + STREET_WIDTH / 2 + SIDEWALK;
                double maxY = ROWS[r + 1] - STREET_WIDTH / 2 - SIDEWALK;
                double lotW = (maxX - minX - LOT_GAP) / 2;
                double lotH = (maxY - minY - LOT_GAP) / 2;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        double x0 = minX + i * (lotW + LOT_GAP) + random.nextDouble() * 5;
                        double y0 = minY + j * (lotH + LOT_GAP) + random.nextDouble() * 5;
                        double x1 = minX + i * (lotW + LOT_GAP) + lotW - random.nextDouble() * 5;
                        double y1 = minY + j * (lotH + LOT_GAP) + lotH - random.nextDouble() * 5;
                        double height = 10 + random.nextDouble() * random.nextDouble() * 60;
                        buildings.add(new Obstacle(id++, Obstacle.Kind.BUILDING,
                                OrientedBox.axisAligned(x0, y0, x1, y1), height, "Building"));
                    }
                }
            }
        }
    }

    private static boolean isPark(int column, int row) {
        for (int[] park : PARKS) {
            if (park[0] == column && park[1] == row) {
                return true;
            }
        }
        return false;
    }

    /** Park blocks as axis-aligned rectangles {minX, minY, maxX, maxY}, for drawing grass. */
    public List<double[]> parks() {
        List<double[]> result = new ArrayList<>();
        for (int[] park : PARKS) {
            result.add(new double[] {
                    COLUMNS[park[0]] + STREET_WIDTH / 2, ROWS[park[1]] + STREET_WIDTH / 2,
                    COLUMNS[park[0] + 1] - STREET_WIDTH / 2, ROWS[park[1] + 1] - STREET_WIDTH / 2});
        }
        return result;
    }

    private static Point2 lerp(Point2 a, Point2 b, double t) {
        return new Point2(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
    }

    public List<Street> streets() {
        return List.copyOf(streets);
    }

    /** Road from the circuit's top junction to the city. */
    public Street connector() {
        return connector;
    }

    public List<Road> roads() {
        return List.copyOf(roads);
    }

    public List<Marking> markings() {
        return List.copyOf(markings);
    }

    public List<Obstacle> buildings() {
        return List.copyOf(buildings);
    }

    public List<Place> places() {
        return List.copyOf(places);
    }
}
