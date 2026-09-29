package com.selfdriving.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The drivable road network at lane level, built from map roads ({@link RoadSource}).
 *
 * <p><b>Left-hand traffic</b> (as in India): on a two-way road each direction's lanes lie to the
 * left of the centre line. Lanes are numbered from the kerb: lane 0 is the leftmost (slow) lane,
 * the highest number is next to the centre line or median (overtaking lane).
 *
 * <p>How the network is made:
 * <ol>
 *   <li>Points shared by two or more roads (and road ends) become junction nodes. Nodes that are
 *       joined by very short pieces of road (typical where two divided roads cross) are merged
 *       into one junction.</li>
 *   <li>Each piece of road between junctions becomes one {@link Link} per direction of travel,
 *       trimmed back so it ends at the edge of the junction.</li>
 *   <li>Inside each junction, {@link Connector}s join incoming lanes to outgoing lanes: left
 *       turns from the leftmost lane, right turns (across oncoming traffic) from the rightmost,
 *       straight on from any lane. Their paths are smooth curves, so the turning radius is real.</li>
 *   <li>Connectors whose paths cross or merge get a {@link Conflict} each way, marked with who
 *       must give way: at signals, turning traffic yields to straight-on traffic; elsewhere the
 *       more important road has priority, then traffic from the right.</li>
 *   <li>Junctions near a mapped traffic signal get a signal plan: approaches are grouped by
 *       direction into phases that take turns (green, amber, all-red).</li>
 * </ol>
 *
 * <p>Immutable after construction and shared by all threads.
 */
public final class RoadNetwork {

    /** Movement through a junction. */
    public enum Turn { STRAIGHT, LEFT, RIGHT, UTURN }

    /** Signal aspect for an approach. */
    public enum Signal { NONE, GREEN, AMBER, RED }

    private static final double CLUSTER_PIECE_LENGTH = 22;
    private static final double CLUSTER_MAX_SPAN = 48;
    private static final double MERGE_DISTANCE = 6;
    private static final double INTERNAL_MAX_LENGTH = 60;
    private static final double SIGNAL_SEARCH = 22;
    private static final double TURN_THRESHOLD = Math.toRadians(35);
    private static final double UTURN_THRESHOLD = Math.toRadians(150);
    private static final double CONFLICT_DISTANCE = 2.6;
    private static final double GREEN = 22;
    private static final double GREEN_MAJOR_BONUS = 10;
    private static final double AMBER = 3;
    private static final double ALL_RED = 2;
    private static final double GRID_CELL = 40;
    private static final double CORNER_ROOM = 4.5;

    /** A junction (or the end of a road). */
    public static final class Junction {
        private final int id;
        private final Point2 position;
        private final List<Point2> members;
        private final List<Integer> incoming = new ArrayList<>();
        private final List<Integer> outgoing = new ArrayList<>();
        private final List<Integer> connectors = new ArrayList<>();
        private boolean boundary;
        private SignalPlan signal;
        private String name = "";

        Junction(int id, Point2 position, List<Point2> members) {
            this.id = id;
            this.position = position;
            this.members = List.copyOf(members);
        }

        public int id() {
            return id;
        }

        public Point2 position() {
            return position;
        }

        /** The map nodes merged into this junction. */
        public List<Point2> members() {
            return members;
        }

        public List<Integer> incoming() {
            return Collections.unmodifiableList(incoming);
        }

        public List<Integer> outgoing() {
            return Collections.unmodifiableList(outgoing);
        }

        public List<Integer> connectors() {
            return Collections.unmodifiableList(connectors);
        }

        /** Where a road leaves the map: traffic appears and disappears here. */
        public boolean isBoundary() {
            return boundary;
        }

        /** Signal plan, or null for junctions without signals. */
        public SignalPlan signal() {
            return signal;
        }

        public boolean isSignalised() {
            return signal != null;
        }

        /** Names of the roads that meet here, e.g. "Brigade Road / MG Road". */
        public String name() {
            return name;
        }
    }

    /** One direction of travel on a road between two junctions, with its lanes. */
    public static final class Link {
        private final int id;
        private final int from;
        private final int to;
        private final String name;
        private final int rank;
        private final Polyline centre;
        private final int lanes;
        private final double laneWidth;
        private final double base;
        private final double speedLimit;
        private final boolean roundabout;
        private final int piece;
        private final boolean rendered;
        private final Polyline[] laneLines;
        private int reverseId = -1;
        private int phase = -1;
        private long startNode;
        private long endNode;

        Link(int id, int from, int to, String name, int rank, Polyline centre, int lanes, double laneWidth,
             double base, double speedLimit, boolean roundabout, int piece, boolean rendered) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.name = name;
            this.rank = rank;
            this.centre = centre;
            this.lanes = lanes;
            this.laneWidth = laneWidth;
            this.base = base;
            this.speedLimit = speedLimit;
            this.roundabout = roundabout;
            this.piece = piece;
            this.rendered = rendered;
            this.laneLines = new Polyline[lanes];
            for (int i = 0; i < lanes; i++) {
                laneLines[i] = centre.offset(laneOffset(i));
            }
        }

        public int id() {
            return id;
        }

        /** Junction it starts from. */
        public int from() {
            return from;
        }

        /** Junction it leads to. */
        public int to() {
            return to;
        }

        public String name() {
            return name;
        }

        /** Road importance (right of way). */
        public int rank() {
            return rank;
        }

        /** Reference line (road centre line for two-way roads) in the direction of travel. */
        public Polyline centre() {
            return centre;
        }

        public double length() {
            return centre.length();
        }

        public int lanes() {
            return lanes;
        }

        public double laneWidth() {
            return laneWidth;
        }

        /** Sideways position of a lane's centre from the reference line (positive = left), m. */
        public double laneOffset(int lane) {
            return base + (lanes - lane - 0.5) * laneWidth;
        }

        /** Centre line of a lane (0 = leftmost). */
        public Polyline lane(int lane) {
            return laneLines[Math.max(0, Math.min(lanes - 1, lane))];
        }

        /** Left edge of the carriageway half this link uses, from the reference line, m. */
        public double leftEdge() {
            return base + lanes * laneWidth;
        }

        /** Right edge (towards the centre line or the median), m. */
        public double rightEdge() {
            return base;
        }

        public double speedLimit() {
            return speedLimit;
        }

        public boolean isRoundabout() {
            return roundabout;
        }

        /** The same road in the other direction, or -1 for one-way roads. */
        public int reverseId() {
            return reverseId;
        }

        /** Signal phase group at the junction it leads to, or -1. */
        public int phase() {
            return phase;
        }

        /** Which undirected piece of road this is (shared with the reverse link). */
        public int piece() {
            return piece;
        }

        /** Drawn (with paint) by the world model. */
        public boolean isRendered() {
            return rendered;
        }

        /** Where traffic stops for the junction ahead (before the crossing, if signalised), m. */
        public double stopArc(boolean signalised) {
            return Math.max(0, length() - (signalised ? 5.5 : 1.0));
        }
    }

    /** A path through a junction from one lane to another. */
    public static final class Connector {
        private final int id;
        private final int junction;
        private final int fromLink;
        private final int fromLane;
        private final int toLink;
        private final int toLane;
        private final Turn turn;
        private final Polyline path;
        private final List<Conflict> conflicts = new ArrayList<>();

        Connector(int id, int junction, int fromLink, int fromLane, int toLink, int toLane, Turn turn, Polyline path) {
            this.id = id;
            this.junction = junction;
            this.fromLink = fromLink;
            this.fromLane = fromLane;
            this.toLink = toLink;
            this.toLane = toLane;
            this.turn = turn;
            this.path = path;
        }

        public int id() {
            return id;
        }

        public int junction() {
            return junction;
        }

        public int fromLink() {
            return fromLink;
        }

        public int fromLane() {
            return fromLane;
        }

        public int toLink() {
            return toLink;
        }

        public int toLane() {
            return toLane;
        }

        public Turn turn() {
            return turn;
        }

        public Polyline path() {
            return path;
        }

        public double length() {
            return path.length();
        }

        /** Other connectors whose paths cross or merge with this one. */
        public List<Conflict> conflicts() {
            return Collections.unmodifiableList(conflicts);
        }
    }

    /**
     * Where two paths through a junction meet.
     *
     * @param other     the other connector
     * @param arc       distance along this connector to the meeting point, m
     * @param otherArc  distance along the other connector, m
     * @param giveWay   this connector must give way to the other
     */
    public record Conflict(int other, double arc, double otherArc, boolean giveWay) {
    }

    /** Fixed-time signal plan: phases take turns, each green, then amber, then all red. */
    public static final class SignalPlan {
        private final double[] greens;
        private final double offset;
        private final double cycle;

        SignalPlan(double[] greens, double offset) {
            this.greens = greens;
            double total = 0;
            for (double g : greens) {
                total += g + AMBER + ALL_RED;
            }
            this.cycle = total;
            this.offset = offset % total;
        }

        public int phases() {
            return greens.length;
        }

        public double cycle() {
            return cycle;
        }

        /** Aspect for a phase at a time. */
        public Signal state(int phase, double time) {
            if (phase < 0) {
                return Signal.NONE;
            }
            double t = ((time + offset) % cycle + cycle) % cycle;
            for (int p = 0; p < greens.length; p++) {
                if (t < greens[p]) {
                    return p == phase ? Signal.GREEN : Signal.RED;
                }
                t -= greens[p];
                if (t < AMBER) {
                    return p == phase ? Signal.AMBER : Signal.RED;
                }
                t -= AMBER;
                if (t < ALL_RED) {
                    return Signal.RED;
                }
                t -= ALL_RED;
            }
            return Signal.RED;
        }

        /** Seconds until the aspect of a phase next changes. */
        public double timeToChange(int phase, double time) {
            Signal now = state(phase, time);
            for (double dt = 0.25; dt < cycle; dt += 0.25) {
                if (state(phase, time + dt) != now) {
                    return dt;
                }
            }
            return cycle;
        }
    }

    /** A drawn road piece: full centre line through junctions, for the asphalt. */
    public record Surface(Polyline centre, double leftWidth, double rightWidth, int rank) {
    }

    /** Where a car or a point is on the network. */
    public record Position(Link link, int lane, double arc, double offset, double distance) {
    }

    private final List<Junction> junctions = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final List<Connector> connectors = new ArrayList<>();
    private final List<Surface> surfaces = new ArrayList<>();
    private final List<List<Integer>> connectorsFrom = new ArrayList<>();
    private final Map<Long, List<Integer>> grid = new HashMap<>();
    private double maxSpeedLimit;

    private RoadNetwork() {
    }

    // ==== queries ===========================================================================

    public List<Junction> junctions() {
        return Collections.unmodifiableList(junctions);
    }

    public List<Link> links() {
        return Collections.unmodifiableList(links);
    }

    public List<Connector> connectors() {
        return Collections.unmodifiableList(connectors);
    }

    public Junction junction(int id) {
        return junctions.get(id);
    }

    public Link link(int id) {
        return links.get(id);
    }

    public Connector connector(int id) {
        return connectors.get(id);
    }

    /** Connectors leaving the end of a link. */
    public List<Connector> connectorsFrom(int linkId) {
        List<Connector> result = new ArrayList<>();
        for (int id : connectorsFrom.get(linkId)) {
            result.add(connectors.get(id));
        }
        return result;
    }

    /** Connectors from one link to another. */
    public List<Connector> connectorsBetween(int fromLink, int toLink) {
        List<Connector> result = new ArrayList<>();
        for (int id : connectorsFrom.get(fromLink)) {
            if (connectors.get(id).toLink() == toLink) {
                result.add(connectors.get(id));
            }
        }
        return result;
    }

    /** Asphalt pieces to draw. */
    public List<Surface> surfaces() {
        return Collections.unmodifiableList(surfaces);
    }

    public double maxSpeedLimit() {
        return maxSpeedLimit;
    }

    /** Signal aspect for traffic at the end of a link. */
    public Signal signal(Link link, double time) {
        SignalPlan plan = junctions.get(link.to()).signal();
        return plan == null ? Signal.NONE : plan.state(link.phase(), time);
    }

    /**
     * The lane a point is in: the nearest lane centre within {@code maxDistance}, whose direction
     * matches the heading within {@code maxHeadingError} (NaN heading: any direction).
     */
    public Position locate(double x, double y, double heading, double maxDistance, double maxHeadingError) {
        Position best = null;
        for (int id : nearbyLinks(x, y)) {
            Link link = links.get(id);
            Polyline.Projection p = link.centre().project(x, y);
            if (p.distance() > link.lanes() * link.laneWidth() + Math.abs(link.base) + maxDistance) {
                continue;
            }
            if (!Double.isNaN(heading)) {
                double error = Math.abs(Math.atan2(Math.sin(heading - p.heading()), Math.cos(heading - p.heading())));
                if (error > maxHeadingError) {
                    continue;
                }
            }
            // Signed offset from the reference line (positive = left).
            Point2 on = link.centre().pointAt(p.arc());
            double h = p.heading();
            double offset = -(x - on.x()) * Math.sin(h) + (y - on.y()) * Math.cos(h);
            int lane = (int) Math.floor(link.lanes() - (offset - link.base) / link.laneWidth());
            lane = Math.max(0, Math.min(link.lanes() - 1, lane));
            double distance = Math.abs(offset - link.laneOffset(lane));
            if (p.arc() <= 1e-6 || p.arc() >= link.length() - 1e-6) {
                distance = Math.max(distance, Math.hypot(x - on.x(), y - on.y()) - Math.abs(link.laneOffset(lane)));
            }
            if (distance > maxDistance) {
                continue;
            }
            if (best == null || distance < best.distance()) {
                best = new Position(link, lane, p.arc(), offset, distance);
            }
        }
        return best;
    }

    /** Links whose reference line passes near a point (from a 40 m grid). */
    private List<Integer> nearbyLinks(double x, double y) {
        Set<Integer> result = new HashSet<>();
        int cx = (int) Math.floor(x / GRID_CELL);
        int cy = (int) Math.floor(y / GRID_CELL);
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                List<Integer> cell = grid.get(key(cx + i, cy + j));
                if (cell != null) {
                    result.addAll(cell);
                }
            }
        }
        return new ArrayList<>(result);
    }

    private static long key(int cx, int cy) {
        return ((long) cx << 32) ^ (cy & 0xffffffffL);
    }

    // ==== building ==========================================================================

    /** A piece of a source road between two junction nodes. */
    private record Piece(int source, List<Point2> points, long a, long b, double length) {
    }

    /** Builds a network without traffic signals. */
    public static RoadNetwork build(List<RoadSource> sources) {
        return build(sources, Set.of());
    }

    /**
     * Builds the network.
     *
     * @param signalNodes map node ids that carry a traffic signal
     */
    public static RoadNetwork build(List<RoadSource> sources, Set<Long> signalNodes) {
        RoadNetwork net = new RoadNetwork();
        new Builder(net, sources, signalNodes).run();
        return net;
    }

    private static final class Builder {
        private final RoadNetwork net;
        private final List<RoadSource> sources;
        private final Map<Long, Point2> nodePosition = new HashMap<>();
        private final Map<Long, Integer> uses = new HashMap<>();
        private final List<Piece> pieces = new ArrayList<>();
        private final Map<Long, List<Integer>> piecesAt = new HashMap<>();
        private final Map<Long, Long> parent = new HashMap<>();
        private final Map<Long, Integer> junctionOf = new HashMap<>();
        private final Map<Integer, List<int[]>> internal = new HashMap<>(); // junction -> {pieceIndex}
        private final Map<Long, Double> trimAt = new HashMap<>();
        private final Set<Long> signalNodes;

        Builder(RoadNetwork net, List<RoadSource> sources, Set<Long> signalNodes) {
            this.net = net;
            this.sources = sources;
            this.signalNodes = signalNodes;
        }

        void run() {
            findNodes();
            splitPieces();
            cluster();
            createJunctions();
            computeTrims();
            createLinks();
            createConnectors();
            findConflicts();
            createSignals();
            nameJunctions();
            indexLinks();
        }

        // ---- 1. nodes and pieces -----------------------------------------------------------

        private void findNodes() {
            for (RoadSource s : sources) {
                for (int i = 0; i < s.nodes().length; i++) {
                    long id = s.nodes()[i];
                    nodePosition.putIfAbsent(id, s.points().get(i));
                    int extra = i == 0 || i == s.nodes().length - 1 ? 2 : 1;
                    uses.merge(id, extra, Integer::sum);
                }
            }
        }

        private boolean isJunctionNode(long id) {
            return uses.getOrDefault(id, 0) >= 2;
        }

        private void splitPieces() {
            for (int si = 0; si < sources.size(); si++) {
                RoadSource s = sources.get(si);
                List<Point2> current = new ArrayList<>();
                long start = s.nodes()[0];
                current.add(s.points().get(0));
                for (int i = 1; i < s.nodes().length; i++) {
                    current.add(s.points().get(i));
                    long id = s.nodes()[i];
                    if (isJunctionNode(id) || i == s.nodes().length - 1) {
                        if (current.size() >= 2 && !(start == id && current.size() == 2)) {
                            addPiece(si, current, start, id);
                        }
                        current = new ArrayList<>();
                        current.add(s.points().get(i));
                        start = id;
                    }
                }
            }
        }

        private void addPiece(int source, List<Point2> points, long a, long b) {
            Polyline line = new Polyline(points, false);
            int index = pieces.size();
            pieces.add(new Piece(source, List.copyOf(points), a, b, line.length()));
            piecesAt.computeIfAbsent(a, k -> new ArrayList<>()).add(index);
            piecesAt.computeIfAbsent(b, k -> new ArrayList<>()).add(index);
        }

        private int degree(long node) {
            return piecesAt.getOrDefault(node, List.of()).size();
        }

        // ---- 2. merge nodes that form one junction -----------------------------------------------

        private long find(long x) {
            long root = x;
            while (parent.getOrDefault(root, root) != root) {
                root = parent.get(root);
            }
            parent.put(x, root);
            return root;
        }

        private void cluster() {
            Map<Long, List<Long>> members = new HashMap<>();
            for (long node : piecesAt.keySet()) {
                members.put(node, new ArrayList<>(List.of(node)));
            }
            List<long[]> candidates = new ArrayList<>();
            for (Piece p : pieces) {
                if (p.a() != p.b() && p.length() < CLUSTER_PIECE_LENGTH && degree(p.a()) >= 3 && degree(p.b()) >= 3
                        && p.a() >= 0 && p.b() >= 0) {
                    candidates.add(new long[] {p.a(), p.b()});
                }
            }
            List<Long> nodes = new ArrayList<>(piecesAt.keySet());
            for (int i = 0; i < nodes.size(); i++) {
                for (int j = i + 1; j < nodes.size(); j++) {
                    long a = nodes.get(i);
                    long b = nodes.get(j);
                    if (a >= 0 && b >= 0 && degree(a) >= 3 && degree(b) >= 3
                            && nodePosition.get(a).distanceTo(nodePosition.get(b)) < MERGE_DISTANCE) {
                        candidates.add(new long[] {a, b});
                    }
                }
            }
            candidates.sort((x, y) -> Double.compare(nodePosition.get(x[0]).distanceTo(nodePosition.get(x[1])),
                    nodePosition.get(y[0]).distanceTo(nodePosition.get(y[1]))));
            for (long[] c : candidates) {
                long ra = find(c[0]);
                long rb = find(c[1]);
                if (ra == rb) {
                    continue;
                }
                List<Long> merged = new ArrayList<>(members.get(ra));
                merged.addAll(members.get(rb));
                if (span(merged) > CLUSTER_MAX_SPAN) {
                    continue;
                }
                parent.put(rb, ra);
                members.put(ra, merged);
            }
        }

        private double span(List<Long> nodes) {
            double max = 0;
            for (long a : nodes) {
                for (long b : nodes) {
                    max = Math.max(max, nodePosition.get(a).distanceTo(nodePosition.get(b)));
                }
            }
            return max;
        }

        private void createJunctions() {
            Map<Long, List<Long>> groups = new LinkedHashMap<>();
            List<Long> ordered = new ArrayList<>(piecesAt.keySet());
            Collections.sort(ordered);
            for (long node : ordered) {
                groups.computeIfAbsent(find(node), k -> new ArrayList<>()).add(node);
            }
            for (List<Long> group : groups.values()) {
                double x = 0;
                double y = 0;
                List<Point2> positions = new ArrayList<>();
                for (long n : group) {
                    Point2 p = nodePosition.get(n);
                    positions.add(p);
                    x += p.x();
                    y += p.y();
                }
                Junction j = new Junction(net.junctions.size(), new Point2(x / group.size(), y / group.size()), positions);
                for (long n : group) {
                    junctionOf.put(n, j.id);
                    if (n < 0 && degree(n) == 1) {
                        j.boundary = true;
                    }
                }
                net.junctions.add(j);
            }
        }

        // ---- 3. links -----------------------------------------------------------------------

        /** How far roads stop short of a junction node: enough for the widest crossing road. */
        private void computeTrims() {
            for (Map.Entry<Long, List<Integer>> e : piecesAt.entrySet()) {
                long node = e.getKey();
                Junction j = net.junctions.get(junctionOf.get(node));
                int roads = 0;
                for (long other : piecesAt.keySet()) {
                    if (junctionOf.get(other) == j.id) {
                        roads += degree(other);
                    }
                }
                double trim;
                if (j.boundary || roads <= 1) {
                    trim = 0;
                } else if (roads == 2) {
                    trim = 0.5;
                } else {
                    double widest = 0;
                    for (int pi : e.getValue()) {
                        RoadSource s = sources.get(pieces.get(pi).source());
                        widest = Math.max(widest, s.width() / 2);
                    }
                    for (long other : piecesAt.keySet()) {
                        if (other != node && junctionOf.get(other) == j.id) {
                            for (int pi : piecesAt.get(other)) {
                                RoadSource s = sources.get(pieces.get(pi).source());
                                widest = Math.max(widest, s.width() / 2);
                            }
                        }
                    }
                    // Room for kerb corners: turn paths need a radius a car can actually drive
                    // (about 6 m or more), not just the width of the crossing road.
                    trim = widest + CORNER_ROOM;
                }
                trimAt.put(node, trim);
            }
        }

        private void createLinks() {
            for (int pi = 0; pi < pieces.size(); pi++) {
                Piece p = pieces.get(pi);
                RoadSource s = sources.get(p.source());
                int ja = junctionOf.get(p.a());
                int jb = junctionOf.get(p.b());
                Polyline full = new Polyline(p.points(), false).smoothed(2, 6);
                double surfaceLeft = s.oneway() ? s.width() / 2 : s.lanesForward() * s.laneWidth();
                double surfaceRight = s.oneway() ? s.width() / 2 : s.lanesBackward() * s.laneWidth();
                if (s.rendered()) {
                    net.surfaces.add(new Surface(full, surfaceLeft, surfaceRight, s.rank()));
                }
                if (ja == jb && p.length() < INTERNAL_MAX_LENGTH) {
                    internal.computeIfAbsent(ja, k -> new ArrayList<>()).add(new int[] {pi});
                    continue;
                }
                double trimA = trimAt.get(p.a());
                double trimB = trimAt.get(p.b());
                double length = full.length();
                if (trimA + trimB > length * 0.7) {
                    double k = length * 0.7 / (trimA + trimB);
                    trimA *= k;
                    trimB *= k;
                }
                Polyline trimmed = full.slice(trimA, length - trimB);
                double baseForward = s.oneway() ? -s.lanesForward() * s.laneWidth() / 2 : 0;
                Link forward = addLink(ja, jb, s, trimmed, s.lanesForward(), baseForward, pi);
                forward.startNode = p.a();
                forward.endNode = p.b();
                if (!s.oneway() && s.lanesBackward() > 0) {
                    Link backward = addLink(jb, ja, s, trimmed.reversed(), s.lanesBackward(), 0, pi);
                    backward.startNode = p.b();
                    backward.endNode = p.a();
                    forward.reverseId = backward.id;
                    backward.reverseId = forward.id;
                }
            }
        }

        private Link addLink(int from, int to, RoadSource s, Polyline centre, int lanes, double base, int piece) {
            Link link = new Link(net.links.size(), from, to, s.name(), s.rank(), centre, lanes, s.laneWidth(), base,
                    s.speedLimit(), s.roundabout(), piece, s.rendered());
            net.links.add(link);
            net.connectorsFrom.add(new ArrayList<>());
            net.junctions.get(from).outgoing.add(link.id);
            net.junctions.get(to).incoming.add(link.id);
            net.maxSpeedLimit = Math.max(net.maxSpeedLimit, s.speedLimit());
            return link;
        }

        // ---- 4. connectors ----------------------------------------------------------------------

        /** Map node where a link starts. */
        private static long nodeAtStart(Link link) {
            return link.startNode;
        }

        /** Map node where a link ends. */
        private static long nodeAtEnd(Link link) {
            return link.endNode;
        }

        /** Whether a node can be reached from another inside a junction, respecting one-way pieces. */
        private boolean reachable(int junction, long from, long to) {
            if (from == to) {
                return true;
            }
            List<int[]> inside = internal.getOrDefault(junction, List.of());
            Set<Long> seen = new HashSet<>();
            ArrayDeque<Long> queue = new ArrayDeque<>();
            queue.add(from);
            seen.add(from);
            while (!queue.isEmpty()) {
                long n = queue.poll();
                for (int[] entry : inside) {
                    Piece p = pieces.get(entry[0]);
                    boolean oneway = sources.get(p.source()).oneway();
                    long next = -1;
                    boolean found = false;
                    if (p.a() == n) {
                        next = p.b();
                        found = true;
                    } else if (p.b() == n && !oneway) {
                        next = p.a();
                        found = true;
                    }
                    if (found && seen.add(next)) {
                        if (next == to) {
                            return true;
                        }
                        queue.add(next);
                    }
                }
            }
            return false;
        }

        private void createConnectors() {
            for (Junction j : net.junctions) {
                if (j.boundary) {
                    continue;
                }
                for (int inId : j.incoming) {
                    Link in = net.links.get(inId);
                    long endNode = nodeAtEnd(in);
                    List<Link> options = new ArrayList<>();
                    for (int outId : j.outgoing) {
                        Link out = net.links.get(outId);
                        if (out.piece == in.piece && out.id == in.reverseId) {
                            continue; // U-turns only at dead ends (below)
                        }
                        if (!reachable(j.id, endNode, nodeAtStart(out))) {
                            continue;
                        }
                        double turn = turnAngle(in, out);
                        if (Math.abs(turn) > UTURN_THRESHOLD) {
                            continue; // turning back through a median gap
                        }
                        options.add(out);
                    }
                    if (options.isEmpty() && in.reverseId >= 0) {
                        options.add(net.links.get(in.reverseId)); // dead end: turn round
                    }
                    for (Link out : options) {
                        addConnectors(j, in, out, options.size() == 1);
                    }
                }
            }
        }

        private static double turnAngle(Link in, Link out) {
            double h0 = in.centre().headingAt(in.length());
            double h1 = out.centre().headingAt(0);
            return Math.atan2(Math.sin(h1 - h0), Math.cos(h1 - h0));
        }

        private void addConnectors(Junction j, Link in, Link out, boolean onlyWay) {
            double angle = turnAngle(in, out);
            Turn turn;
            if (out.id == in.reverseId) {
                turn = Turn.UTURN;
            } else if (angle > TURN_THRESHOLD) {
                turn = Turn.LEFT;
            } else if (angle < -TURN_THRESHOLD) {
                turn = Turn.RIGHT;
            } else {
                turn = Turn.STRAIGHT;
            }
            List<int[]> lanes = new ArrayList<>();
            int ni = in.lanes;
            int no = out.lanes;
            if (onlyWay) {
                for (int i = 0; i < ni; i++) {
                    lanes.add(new int[] {i, ni == 1 ? 0 : (int) Math.round((double) i * (no - 1) / (ni - 1))});
                }
            } else {
                switch (turn) {
                    case LEFT -> lanes.add(new int[] {0, 0});
                    case RIGHT, UTURN -> lanes.add(new int[] {ni - 1, no - 1});
                    default -> {
                        for (int i = 0; i < ni; i++) {
                            lanes.add(new int[] {i, Math.min(i, no - 1)});
                        }
                    }
                }
            }
            for (int[] pair : lanes) {
                Polyline path = connectorPath(in.lane(pair[0]), out.lane(pair[1]), turn);
                Connector c = new Connector(net.connectors.size(), j.id, in.id, pair[0], out.id, pair[1], turn, path);
                net.connectors.add(c);
                j.connectors.add(c.id);
                net.connectorsFrom.get(in.id).add(c.id);
            }
        }

        /** Cubic Bezier from the end of one lane to the start of another, tangent to both. */
        private static Polyline connectorPath(Polyline from, Polyline to, Turn turn) {
            List<Point2> a = from.points();
            Point2 p0 = a.get(a.size() - 1);
            Point2 p3 = to.points().get(0);
            double h0 = from.headingAt(from.length());
            double h1 = to.headingAt(0);
            double chord = p0.distanceTo(p3);
            if (chord < 0.3) {
                return Polyline.of(false, p0, new Point2(p0.x() + Math.cos(h0) * 0.3, p0.y() + Math.sin(h0) * 0.3));
            }
            double k = switch (turn) {
                case STRAIGHT -> chord / 3;
                case UTURN -> Math.max(4, chord * 1.2);
                default -> chord * 0.39;
            };
            Point2 p1 = new Point2(p0.x() + Math.cos(h0) * k, p0.y() + Math.sin(h0) * k);
            Point2 p2 = new Point2(p3.x() - Math.cos(h1) * k, p3.y() - Math.sin(h1) * k);
            int n = Math.max(4, (int) Math.ceil(chord * (turn == Turn.UTURN ? 3 : 1.2)));
            List<Point2> points = new ArrayList<>(n + 1);
            for (int i = 0; i <= n; i++) {
                double t = (double) i / n;
                double u = 1 - t;
                double b0 = u * u * u;
                double b1 = 3 * u * u * t;
                double b2 = 3 * u * t * t;
                double b3 = t * t * t;
                points.add(new Point2(b0 * p0.x() + b1 * p1.x() + b2 * p2.x() + b3 * p3.x(),
                        b0 * p0.y() + b1 * p1.y() + b2 * p2.y() + b3 * p3.y()));
            }
            return new Polyline(points, false);
        }

        // ---- 5. conflicts --------------------------------------------------------------------

        private void findConflicts() {
            for (Junction j : net.junctions) {
                List<Integer> ids = j.connectors;
                for (int x = 0; x < ids.size(); x++) {
                    for (int y = x + 1; y < ids.size(); y++) {
                        Connector a = net.connectors.get(ids.get(x));
                        Connector b = net.connectors.get(ids.get(y));
                        if (a.fromLink == b.fromLink && a.fromLane == b.fromLane) {
                            continue; // same lane: one behind the other, not crossing
                        }
                        double[] meet = meeting(a, b);
                        if (meet == null) {
                            continue;
                        }
                        boolean aYields = yields(j, a, b);
                        boolean bYields = !aYields && yields(j, b, a);
                        a.conflicts.add(new Conflict(b.id, meet[0], meet[1], aYields));
                        b.conflicts.add(new Conflict(a.id, meet[1], meet[0], bYields));
                    }
                }
            }
        }

        /** First point where two paths cross or come within a car's width: {arcA, arcB}, or null. */
        private static double[] meeting(Connector a, Connector b) {
            if (a.toLink == b.toLink && a.toLane == b.toLane) {
                return new double[] {a.length(), b.length()}; // merging into the same lane
            }
            List<Point2> pa = a.path.points();
            List<Point2> pb = b.path.points();
            double arcA = 0;
            for (int i = 1; i < pa.size(); i++) {
                Point2 a0 = pa.get(i - 1);
                Point2 a1 = pa.get(i);
                double arcB = 0;
                for (int k = 1; k < pb.size(); k++) {
                    Point2 b0 = pb.get(k - 1);
                    Point2 b1 = pb.get(k);
                    double[] t = intersect(a0, a1, b0, b1);
                    if (t != null) {
                        return new double[] {arcA + t[0] * a0.distanceTo(a1), arcB + t[1] * b0.distanceTo(b1)};
                    }
                    arcB += b0.distanceTo(b1);
                }
                arcA += a0.distanceTo(a1);
            }
            // Near misses: paths that pass closer than a vehicle's width (buses too) without crossing.
            double best = CONFLICT_DISTANCE;
            double[] result = null;
            double sa = 0;
            for (int i = 0; i < pa.size(); i++) {
                if (i > 0) {
                    sa += pa.get(i - 1).distanceTo(pa.get(i));
                }
                double sb = 0;
                for (int k = 0; k < pb.size(); k++) {
                    if (k > 0) {
                        sb += pb.get(k - 1).distanceTo(pb.get(k));
                    }
                    double d = pa.get(i).distanceTo(pb.get(k));
                    if (d < best) {
                        best = d;
                        result = new double[] {sa, sb};
                    }
                }
            }
            return result;
        }

        private static double[] intersect(Point2 p, Point2 p2, Point2 q, Point2 q2) {
            double rx = p2.x() - p.x();
            double ry = p2.y() - p.y();
            double sx = q2.x() - q.x();
            double sy = q2.y() - q.y();
            double denominator = rx * sy - ry * sx;
            if (Math.abs(denominator) < 1e-12) {
                return null;
            }
            double qpx = q.x() - p.x();
            double qpy = q.y() - p.y();
            double t = (qpx * sy - qpy * sx) / denominator;
            double u = (qpx * ry - qpy * rx) / denominator;
            return t >= 0 && t <= 1 && u >= 0 && u <= 1 ? new double[] {t, u} : null;
        }

        /** Whether connector a must give way to b (decided before signals are known: see below). */
        private boolean yields(Junction j, Connector a, Connector b) {
            Link ia = net.links.get(a.fromLink);
            Link ib = net.links.get(b.fromLink);
            boolean signalCandidate = hasSignalNearby(j);
            if (!signalCandidate) {
                if (ia.rank != ib.rank) {
                    return ia.rank < ib.rank;
                }
                if (ia.roundabout != ib.roundabout) {
                    return !ia.roundabout;
                }
            }
            int pa = turnPriority(a.turn);
            int pb = turnPriority(b.turn);
            if (pa != pb) {
                return pa < pb;
            }
            // Same kind of movement: give way to traffic approaching from the right.
            Point2 aEnd = last(ia.centre());
            Point2 bEnd = last(ib.centre());
            double h = ia.centre().headingAt(ia.length());
            double side = Math.cos(h) * (bEnd.y() - aEnd.y()) - Math.sin(h) * (bEnd.x() - aEnd.x());
            return side < 0;
        }

        /** Straight on beats turning towards the kerb, which beats turning across traffic. */
        private static int turnPriority(Turn t) {
            return switch (t) {
                case STRAIGHT -> 3;
                case LEFT -> 2;
                case RIGHT -> 1;
                case UTURN -> 0;
            };
        }

        private static Point2 last(Polyline line) {
            return line.points().get(line.points().size() - 1);
        }

        // ---- 6. signals ------------------------------------------------------------------------

        private boolean hasSignalNearby(Junction j) {
            if (j.incoming.size() < 2 && j.outgoing.size() < 2) {
                return false;
            }
            for (long n : signalNodes) {
                Point2 p = nodePosition.get(n);
                if (p == null) {
                    continue;
                }
                double reach = SIGNAL_SEARCH;
                for (Point2 m : j.members) {
                    if (m.distanceTo(p) < reach) {
                        return true;
                    }
                }
            }
            return false;
        }

        private void createSignals() {
            for (Junction j : net.junctions) {
                if (j.boundary || j.incoming.size() < 2 || !hasSignalNearby(j)) {
                    continue;
                }
                // Group approaches by the axis they come along (opposite approaches share a phase).
                List<List<Integer>> groups = new ArrayList<>();
                List<Double> axes = new ArrayList<>();
                List<Integer> sorted = new ArrayList<>(j.incoming);
                sorted.sort((x, y) -> Integer.compare(net.links.get(y).rank, net.links.get(x).rank));
                for (int inId : sorted) {
                    Link in = net.links.get(inId);
                    double axis = ((in.centre().headingAt(in.length()) % Math.PI) + Math.PI) % Math.PI;
                    int found = -1;
                    for (int g = 0; g < axes.size(); g++) {
                        double d = Math.abs(axis - axes.get(g));
                        d = Math.min(d, Math.PI - d);
                        if (d < Math.toRadians(40)) {
                            found = g;
                            break;
                        }
                    }
                    if (found < 0) {
                        groups.add(new ArrayList<>());
                        axes.add(axis);
                        found = groups.size() - 1;
                    }
                    groups.get(found).add(inId);
                }
                if (groups.size() < 2) {
                    continue; // everything from one direction: nothing to separate
                }
                double[] greens = new double[groups.size()];
                for (int g = 0; g < groups.size(); g++) {
                    greens[g] = GREEN + (g == 0 ? GREEN_MAJOR_BONUS : 0);
                    for (int inId : groups.get(g)) {
                        net.links.get(inId).phase = g;
                    }
                }
                j.signal = new SignalPlan(greens, (j.id * 37.0) % 60);
                // Different phases never have green together: those paths only need to check for
                // cars still in the junction, not give way.
                for (int cid : j.connectors) {
                    Connector c = net.connectors.get(cid);
                    int phase = net.links.get(c.fromLink).phase;
                    List<Conflict> updated = new ArrayList<>();
                    for (Conflict conflict : c.conflicts) {
                        int otherPhase = net.links.get(net.connectors.get(conflict.other()).fromLink).phase;
                        updated.add(phase == otherPhase ? conflict
                                : new Conflict(conflict.other(), conflict.arc(), conflict.otherArc(), false));
                    }
                    c.conflicts.clear();
                    c.conflicts.addAll(updated);
                }
            }
        }

        // ---- 7. names and index -----------------------------------------------------------------

        private void nameJunctions() {
            for (Junction j : net.junctions) {
                Set<String> names = new java.util.LinkedHashSet<>();
                for (int id : j.incoming) {
                    String n = net.links.get(id).name;
                    if (!n.isBlank()) {
                        names.add(n);
                    }
                }
                for (int id : j.outgoing) {
                    String n = net.links.get(id).name;
                    if (!n.isBlank()) {
                        names.add(n);
                    }
                }
                j.name = String.join(" / ", names);
            }
        }

        private void indexLinks() {
            for (Link link : net.links) {
                Set<Long> cells = new HashSet<>();
                List<Point2> pts = link.centre().points();
                for (int i = 0; i < pts.size(); i++) {
                    Point2 p = pts.get(i);
                    cells.add(key((int) Math.floor(p.x() / GRID_CELL), (int) Math.floor(p.y() / GRID_CELL)));
                    if (i > 0) {
                        Point2 q = pts.get(i - 1);
                        int steps = (int) Math.ceil(p.distanceTo(q) / (GRID_CELL / 2));
                        for (int s = 1; s < steps; s++) {
                            double t = (double) s / steps;
                            double x = q.x() + (p.x() - q.x()) * t;
                            double y = q.y() + (p.y() - q.y()) * t;
                            cells.add(key((int) Math.floor(x / GRID_CELL), (int) Math.floor(y / GRID_CELL)));
                        }
                    }
                }
                for (long cell : cells) {
                    net.grid.computeIfAbsent(cell, k -> new ArrayList<>()).add(link.id);
                }
            }
        }
    }

}
