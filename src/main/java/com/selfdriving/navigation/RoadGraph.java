package com.selfdriving.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.RoadNetwork;
import com.selfdriving.world.World;

/**
 * The road network as a directed graph for route planning. Junctions are nodes; each direction
 * of travel on a road between two junctions is an edge. Which edge may follow which comes from
 * the junction's turn paths ({@link RoadNetwork.Connector}), so banned turns and one-way streets
 * are respected automatically.
 *
 * <p>The graph is immutable; closed roads are kept separately so it can be shared by every thread.
 */
public final class RoadGraph {

    /** A junction. */
    public record Node(int id, Point2 position, String name) {
    }

    /**
     * One direction of travel on a road between two junctions.
     *
     * @param id         edge number (same as the network link id)
     * @param from       start junction
     * @param to         end junction
     * @param roadName   road name for directions
     * @param centre     reference line in the direction of travel
     * @param lane       centre of the left-hand (kerb) lane
     * @param speedLimit m/s
     * @param reverseId  the same road in the other direction, or -1 for one-way roads
     * @param link       the network link with all its lanes
     */
    public record Edge(int id, Node from, Node to, String roadName, Polyline centre, Polyline lane,
                       double speedLimit, int reverseId, RoadNetwork.Link link) {

        public double length() {
            return link.length();
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
     * @param arc      distance along the edge, m
     * @param distance distance from the centre of its lane, m
     * @param lane     lane number (0 = leftmost)
     */
    public record Location(Edge edge, double arc, double distance, int lane) {
    }

    private static final double MAX_LOCATE_DISTANCE = 4.0;
    private static final double MAX_LOCATE_HEADING_ERROR = Math.toRadians(60);

    private final RoadNetwork network;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<List<Edge>> successors = new ArrayList<>();

    private RoadGraph(RoadNetwork network) {
        this.network = network;
    }

    public static RoadGraph build(World world) {
        return build(world.network());
    }

    public static RoadGraph build(RoadNetwork network) {
        RoadGraph g = new RoadGraph(network);
        for (RoadNetwork.Junction j : network.junctions()) {
            g.nodes.add(new Node(j.id(), j.position(), j.name()));
        }
        for (RoadNetwork.Link l : network.links()) {
            g.edges.add(new Edge(l.id(), g.nodes.get(l.from()), g.nodes.get(l.to()), l.name(), l.centre(), l.lane(0),
                    l.speedLimit(), l.reverseId(), l));
        }
        for (RoadNetwork.Link l : network.links()) {
            Map<Integer, Edge> next = new LinkedHashMap<>();
            for (RoadNetwork.Connector c : network.connectorsFrom(l.id())) {
                if (c.turn() != RoadNetwork.Turn.UTURN) {
                    next.putIfAbsent(c.toLink(), g.edges.get(c.toLink()));
                }
            }
            g.successors.add(List.copyOf(next.values()));
        }
        return g;
    }

    public RoadNetwork network() {
        return network;
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

    /** Edges that can be driven onto from the end of an edge (no U-turns). */
    public List<Edge> successors(Edge edge) {
        return successors.get(edge.id());
    }

    /** Edges leaving a junction. */
    public List<Edge> outgoing(Node node) {
        List<Edge> result = new ArrayList<>();
        for (int id : network.junction(node.id()).outgoing()) {
            result.add(edges.get(id));
        }
        return result;
    }

    /** Highest speed limit in the network, m/s (for the A* heuristic). */
    public double maxSpeedLimit() {
        return network.maxSpeedLimit();
    }

    /**
     * Finds the lane a car is driving in: the nearest lane within 4 m of its centre whose direction
     * matches the car's heading within 60 degrees.
     */
    public Optional<Location> locate(double x, double y, double heading) {
        RoadNetwork.Position p = network.locate(x, y, heading, MAX_LOCATE_DISTANCE, MAX_LOCATE_HEADING_ERROR);
        return p == null ? Optional.empty()
                : Optional.of(new Location(edges.get(p.link().id()), p.arc(), p.distance(), p.lane()));
    }
}
