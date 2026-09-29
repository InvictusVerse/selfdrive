package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;

/**
 * A vehicle test site: a three-lane oval circuit, a skidpad circle for cornering tests, an access
 * road, and a braking zone with a painted marker every 10 m on the main straight.
 *
 * <p>The whole ground is flat and drivable; roads and paint are there to aim at and to measure
 * against. The layout is generated from a few numbers, so it is easy to change.
 */
public final class ProvingGround {

    /** Half the side length of the square ground area, m. */
    public static final double GROUND_HALF_SIZE = 900;

    private static final double STRAIGHT_HALF_LENGTH = 350;
    private static final double OVAL_RADIUS = 150;
    private static final double CIRCUIT_WIDTH = 11;
    private static final int CIRCUIT_LANES = 3;

    private static final double SKIDPAD_RADIUS = 45;
    private static final double SKIDPAD_WIDTH = 8;
    private static final double SKIDPAD_CENTRE_Y = OVAL_RADIUS;

    private static final double BRAKE_ZONE_START_X = 0;
    private static final double BRAKE_ZONE_LENGTH = 200;
    private static final double START_LINE_X = -300;

    private static final double EDGE_LINE_WIDTH = 0.15;
    private static final double LANE_LINE_WIDTH = 0.12;
    private static final double SAMPLE_STEP = 3;

    private final List<Road> roads = new ArrayList<>();
    private final List<Marking> markings = new ArrayList<>();
    private final Pose start;

    public ProvingGround() {
        Road circuit = new Road("Circuit", ovalCentreLine(), CIRCUIT_WIDTH, CIRCUIT_LANES);
        List<Point2> ring = Polyline.arc(0, SKIDPAD_CENTRE_Y, SKIDPAD_RADIUS, 0, 2 * Math.PI, SAMPLE_STEP);
        Road skidpad = new Road("Skidpad", new Polyline(ring.subList(0, ring.size() - 1), true),
                SKIDPAD_WIDTH, 1);
        Road access = new Road("Access road",
                new Polyline(Polyline.straight(new Point2(0, CIRCUIT_WIDTH / 2),
                        new Point2(0, SKIDPAD_CENTRE_Y - SKIDPAD_RADIUS - SKIDPAD_WIDTH / 2), SAMPLE_STEP), false),
                7, 2);
        roads.add(circuit);
        roads.add(skidpad);
        roads.add(access);

        for (Road road : roads) {
            addEdgeLines(road);
            addLaneLines(road);
        }
        addTransverse(START_LINE_X, 0.6);
        for (double x = BRAKE_ZONE_START_X; x <= BRAKE_ZONE_START_X + BRAKE_ZONE_LENGTH + 1e-9; x += 10) {
            boolean major = Math.round(x - BRAKE_ZONE_START_X) % 50 == 0;
            addTransverse(x, major ? 0.4 : 0.2);
        }

        start = new Pose(START_LINE_X - 30, circuitRightLaneOffset(), 0);
    }

    /** Stadium-shaped loop driven anticlockwise: east along the main straight (y = 0). */
    private static Polyline ovalCentreLine() {
        List<Point2> points = new ArrayList<>();
        double h = STRAIGHT_HALF_LENGTH;
        double r = OVAL_RADIUS;
        List<Point2> bottom = Polyline.straight(new Point2(-h, 0), new Point2(h, 0), SAMPLE_STEP);
        points.addAll(bottom.subList(0, bottom.size() - 1));
        List<Point2> east = Polyline.arc(h, r, r, -Math.PI / 2, Math.PI / 2, SAMPLE_STEP);
        points.addAll(east.subList(0, east.size() - 1));
        List<Point2> top = Polyline.straight(new Point2(h, 2 * r), new Point2(-h, 2 * r), SAMPLE_STEP);
        points.addAll(top.subList(0, top.size() - 1));
        List<Point2> west = Polyline.arc(-h, r, r, Math.PI / 2, 3 * Math.PI / 2, SAMPLE_STEP);
        points.addAll(west.subList(0, west.size() - 1));
        return new Polyline(points, true);
    }

    private void addEdgeLines(Road road) {
        double inset = road.width() / 2 - 0.3;
        markings.add(new Marking(road.centre().offset(inset), EDGE_LINE_WIDTH, Marking.Kind.EDGE));
        markings.add(new Marking(road.centre().offset(-inset), EDGE_LINE_WIDTH, Marking.Kind.EDGE));
    }

    private void addLaneLines(Road road) {
        for (int lane = 1; lane < road.lanes(); lane++) {
            double offset = -road.width() / 2 + lane * road.laneWidth();
            for (Polyline dash : road.centre().offset(offset).dashes(3, 6)) {
                markings.add(new Marking(dash, LANE_LINE_WIDTH, Marking.Kind.LANE));
            }
        }
    }

    /** A line across the main straight at an x position. */
    private void addTransverse(double x, double width) {
        double half = CIRCUIT_WIDTH / 2 - 0.3;
        markings.add(new Marking(Polyline.of(false, new Point2(x, -half), new Point2(x, half)), width,
                Marking.Kind.TRANSVERSE));
    }

    public List<Road> roads() {
        return List.copyOf(roads);
    }

    public List<Marking> markings() {
        return List.copyOf(markings);
    }

    // ---- Road network hooks -------------------------------------------------------------

    /** Junction on the main straight where the skidpad access road starts. */
    public Point2 mainJunction() {
        return new Point2(0, 0);
    }

    /** Junction on the top straight where the road to the city starts. */
    public Point2 topJunction() {
        return new Point2(0, 2 * OVAL_RADIUS);
    }

    /** End of the skidpad access road. */
    public Point2 skidpadEntrance() {
        return new Point2(0, SKIDPAD_CENTRE_Y - SKIDPAD_RADIUS - SKIDPAD_WIDTH / 2);
    }

    /** Circuit from the main junction round the east bend to the top junction (driving direction). */
    public Polyline circuitEastHalf() {
        double h = STRAIGHT_HALF_LENGTH;
        double r = OVAL_RADIUS;
        List<Point2> points = new ArrayList<>(Polyline.straight(mainJunction(), new Point2(h, 0), SAMPLE_STEP));
        points.remove(points.size() - 1);
        List<Point2> bend = Polyline.arc(h, r, r, -Math.PI / 2, Math.PI / 2, SAMPLE_STEP);
        points.addAll(bend.subList(0, bend.size() - 1));
        points.addAll(Polyline.straight(new Point2(h, 2 * r), topJunction(), SAMPLE_STEP));
        return new Polyline(points, false);
    }

    /** Circuit from the top junction round the west bend back to the main junction. */
    public Polyline circuitWestHalf() {
        double h = STRAIGHT_HALF_LENGTH;
        double r = OVAL_RADIUS;
        List<Point2> points = new ArrayList<>(Polyline.straight(topJunction(), new Point2(-h, 2 * r), SAMPLE_STEP));
        points.remove(points.size() - 1);
        List<Point2> bend = Polyline.arc(-h, r, r, Math.PI / 2, 3 * Math.PI / 2, SAMPLE_STEP);
        points.addAll(bend.subList(0, bend.size() - 1));
        points.addAll(Polyline.straight(new Point2(-h, 0), mainJunction(), SAMPLE_STEP));
        return new Polyline(points, false);
    }

    /** Offset of the right-hand lane's centre from the circuit centre line (negative = right), m. */
    public double circuitRightLaneOffset() {
        return -CIRCUIT_WIDTH / 2 + CIRCUIT_WIDTH / CIRCUIT_LANES / 2;
    }

    /** Circuit width, m. */
    public double circuitWidth() {
        return CIRCUIT_WIDTH;
    }

    /** Where the car starts: right-hand lane of the main straight, facing east. */
    public Pose start() {
        return start;
    }

    /** Start of the braking zone on the main straight (x coordinate), m. */
    public double brakeZoneStartX() {
        return BRAKE_ZONE_START_X;
    }

    /** Bounding box of all roads: {minX, minY, maxX, maxY}. */
    public double[] roadBounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Road road : roads) {
            for (Point2 p : road.centre().points()) {
                minX = Math.min(minX, p.x() - road.width());
                minY = Math.min(minY, p.y() - road.width());
                maxX = Math.max(maxX, p.x() + road.width());
                maxY = Math.max(maxY, p.y() + road.width());
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }
}
