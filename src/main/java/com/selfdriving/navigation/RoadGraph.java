package com.selfdriving.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.selfdriving.world.City;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.ProvingGround;
import com.selfdriving.world.World;

/**
 * The road network as a directed graph. Junctions are nodes; each direction of travel on a
 * road is its own edge, with the lane the car should drive in, its length and speed limit.
 *
 * <p>Traffic drives on the right. Two-way streets have one edge per direction; the circuit is
 * one-way (anticlockwise). The graph is immutable; which roads are closed is kept separately so
 * the same graph can be shared by every thread.
 */
public final class RoadGraph {

    /** A junction. */
    public record Node(int id, Point2 position, String name) {
    }

    /**
     * One direction of travel on a road between two junctions.
     *
     * @param id         edge number
     * @param from       start junction
     * @param to         end junction
     * @param roadName   road name for directions
     * @param centre     road centre line in the direction of travel
     * @param lane       centre of the lane the car drives in
     * @param speedLimit m/s
     * @param reverseId  id of the edge in the opposite direction, or -1 for one-way roads
     */
    public record Edge(int id, Node from, Node to, String roadName, Polyline centre, Polyline lane,
                       double speedLimit, int reverseId) {

        /** Lane length, m. */
        public double length() {
            return lane.length();
        }

        /** Time to drive the edge at the speed limit, s. */
        public double travelTime() {
            return length() / speedLimit;
        }
    }

    /**
     * Where a car is on the network.
     *
     * @param edge     the edge it is driving on
     * @param arc      distance along the edge's lane, m
     * @param distance distance from the lane centre, m
     */
    public record Location(Edge edge, double arc, double distance) {
    }

    private static final double CIRCUIT_SPEED_LIMIT = 100 / 3.6;
    private static final double ACCESS_SPEED_LIMIT = 40 / 3.6;
    private static final double ACCESS_LANE_OFFSET = -1.75;
    private static final double MAX_LOCATE_DISTANCE = 8.0;
    private static final double MAX_LOCATE_HEADING_ERROR = Math.toRadians(60);

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<List<Edge>> outgoing = new ArrayList<>();
    private double maxSpeedLimit;

    private RoadGraph() {
    }

    /** Builds the network for the proving ground, the connector and the city grid. */
    public static RoadGraph build(World world) {
        RoadGraph g = new RoadGraph();
        ProvingGround ground = world.provingGround();

        Node main = g.addNode(ground.mainJunction(), "Proving Ground");
        Node top = g.addNode(ground.topJunction(), "Circuit north junction");
        Node skidpad = g.addNode(ground.skidpadEntrance(), "Skidpad");

        double circuitLane = ground.circuitRightLaneOffset();
        g.addEdge(main, top, "Circuit", ground.circuitEastHalf(), circuitLane, CIRCUIT_SPEED_LIMIT, false);
        g.addEdge(top, main, "Circuit", ground.circuitWestHalf(), circuitLane, CIRCUIT_SPEED_LIMIT, false);
        g.addEdge(main, skidpad, "Skidpad access",
                new Polyline(Polyline.straight(main.position(), skidpad.position(), 4), false),
                ACCESS_LANE_OFFSET, ACCESS_SPEED_LIMIT, true);

        Node[][] grid = new Node[City.COLUMNS.length][City.ROWS.length];
        for (int c = 0; c < City.COLUMNS.length; c++) {
            for (int r = 0; r < City.ROWS.length; r++) {
                grid[c][r] = g.addNode(City.node(c, r), "Junction " + (char) ('A' + c) + (r + 1));
            }
        }
        double streetLane = -City.STREET_WIDTH / 4;
        City.Street connector = world.city().connector();
        g.addEdge(top, g.nodeAt(connector.to()).orElseThrow(), "Connector",
                new Polyline(Polyline.straight(connector.from(), connector.to(), 4), false),
                streetLane, City.CONNECTOR_SPEED_LIMIT, true);
        for (City.Street street : world.city().streets()) {
            Node a = g.nodeAt(street.from()).orElseThrow();
            Node b = g.nodeAt(street.to()).orElseThrow();
            g.addEdge(a, b, streetName(street), new Polyline(Polyline.straight(street.from(), street.to(), 4), false),
                    streetLane, City.STREET_SPEED_LIMIT, true);
        }

        // Named places become junction names.
        for (var place : world.places()) {
            g.nodeAt(place.location()).ifPresent(n -> g.nodes.set(n.id(), new Node(n.id(), n.position(), place.name())));
        }
        return g;
    }

    private static final String[] ROW_STREETS = {"Harbour Road", "Park Street", "Market Street", "Station Road"};

    /** East-west streets are named by row; north-south streets are numbered avenues. */
    private static String streetName(City.Street street) {
        if (street.from().y() == street.to().y()) {
            for (int r = 0; r < City.ROWS.length; r++) {
                if (City.ROWS[r] == street.from().y()) {
                    return ROW_STREETS[r];
                }
            }
        }
        for (int c = 0; c < City.COLUMNS.length; c++) {
            if (City.COLUMNS[c] == street.from().x()) {
                return ordinal(c + 1) + " Avenue";
            }
        }
        return "Street";
    }

    private static String ordinal(int n) {
        return n + switch (n) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private Node addNode(Point2 position, String name) {
        Node node = new Node(nodes.size(), position, name);
        nodes.add(node);
        outgoing.add(new ArrayList<>());
        return node;
    }

    private void addEdge(Node a, Node b, String name, Polyline centre, double laneOffset, double speedLimit,
                         boolean twoWay) {
        int forwardId = edges.size();
        int reverseId = twoWay ? forwardId + 1 : -1;
        Edge forward = new Edge(forwardId, a, b, name, centre, centre.offset(laneOffset), speedLimit, reverseId);
        edges.add(forward);
        outgoing.get(a.id()).add(forward);
        if (twoWay) {
            Polyline back = centre.reversed();
            Edge reverse = new Edge(reverseId, b, a, name, back, back.offset(laneOffset), speedLimit, forwardId);
            edges.add(reverse);
            outgoing.get(b.id()).add(reverse);
        }
        maxSpeedLimit = Math.max(maxSpeedLimit, speedLimit);
    }

    /** The node at (almost exactly) a position. */
    public Optional<Node> nodeAt(Point2 position) {
        for (Node node : nodes) {
            if (node.position().distanceTo(position) < 0.5) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    public List<Node> nodes() {
        return Collections.unmodifiableList(nodes);
    }

    public List<Edge> edges() {
        return Collections.unmodifiableList(edges);
    }

    public Node node(int id) {
        return nodes.get(id);
    }

    public Edge edge(int id) {
        return edges.get(id);
    }

    /** Edges leaving a junction. */
    public List<Edge> outgoing(Node node) {
        return Collections.unmodifiableList(outgoing.get(node.id()));
    }

    /** Highest speed limit in the network, m/s (for the A* heuristic). */
    public double maxSpeedLimit() {
        return maxSpeedLimit;
    }

    /**
     * Finds the lane a car is driving in: the nearest lane within 8 m whose direction matches
     * the car's heading within 60 degrees.
     */
    public Optional<Location> locate(double x, double y, double heading) {
        Location best = null;
        for (Edge edge : edges) {
            Polyline.Projection p = edge.lane().project(x, y);
            if (p.distance() > MAX_LOCATE_DISTANCE) {
                continue;
            }
            double headingError = Math.abs(Math.atan2(Math.sin(heading - p.heading()), Math.cos(heading - p.heading())));
            if (headingError > MAX_LOCATE_HEADING_ERROR) {
                continue;
            }
            if (best == null || p.distance() < best.distance()) {
                best = new Location(edge, p.arc(), p.distance());
            }
        }
        return Optional.ofNullable(best);
    }
}
