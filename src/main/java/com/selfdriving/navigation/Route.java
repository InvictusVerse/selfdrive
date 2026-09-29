package com.selfdriving.navigation;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.RoadNetwork;

/**
 * A planned drive at lane level: the exact line to follow, a speed for every point, the
 * turn-by-turn directions, and where each junction starts.
 *
 * <p>The line runs along lane centres. Before a junction the car moves smoothly into a lane
 * that allows the next turn (left turns from the left lane, right turns from the right lane, as
 * traffic keeps left), and through the junction it follows the junction's turn path.
 *
 * <p><b>Speeds.</b> Each point gets the road's limit, lowered for bends: {@code v = sqrt(a / k)}
 * where k is the path curvature and a is the lateral acceleration drivers accept, which is
 * higher in slow, tight turns than in fast bends (design values of about 0.30 g at 20 km/h
 * falling to about 0.16 g at 100 km/h). A backward pass then makes sure the car can always
 * brake comfortably in time ({@code v^2 = v_next^2 + 2 a d}), ending at 0 at the destination.
 *
 * <p>Immutable, so it can be shared with the display.
 */
public final class Route {

    /** Distance between path points, m. */
    public static final double SPACING = 1.5;

    /** Comfortable braking used for the speed profile, m/s^2. */
    public static final double COMFORT_DECEL = 2.0;

    /** Lateral acceleration accepted in slow, tight turns (about 20 km/h), m/s^2. */
    public static final double LOW_SPEED_LATERAL = 3.0;

    /** Lateral acceleration accepted in fast bends (about 100 km/h and above), m/s^2. */
    public static final double HIGH_SPEED_LATERAL = 1.6;

    private static final double LANE_CHANGE_LENGTH = 40;
    private static final double LANE_CHANGE_DONE_BEFORE_JUNCTION = 12;
    private static final double LANE_CHANGE_START = 10;

    /** A direction for the driver, placed where the manoeuvre starts. */
    public record Maneuver(double arc, Type type, String road) {

        public enum Type { LEFT, RIGHT, UTURN, ARRIVE }

        /** Short instruction, e.g. "Turn left onto Brigade Road". */
        public String instruction() {
            return switch (type) {
                case LEFT -> "Turn left onto " + road;
                case RIGHT -> "Turn right onto " + road;
                case UTURN -> "Make a U-turn onto " + road;
                case ARRIVE -> "Arrive at " + road;
            };
        }
    }

    /**
     * Where the route crosses a junction.
     *
     * @param stopArc     where to stop if the car has to wait (stop line), m along the route
     * @param entryArc    where the path enters the junction, m
     * @param exitArc     where it leaves, m
     * @param link        the approach link
     * @param connector   the turn path used
     * @param junction    the junction
     */
    public record JunctionEntry(double stopArc, double entryArc, double exitArc, int link, int connector,
                                int junction) {
    }

    /**
     * A stretch of the route on one link (between junctions) or through one junction.
     *
     * @param startArc  m along the route
     * @param endArc    m along the route
     * @param link      link id, or -1 inside a junction
     * @param connector connector id, or -1 on a link
     * @param linkStart distance along the link where this stretch starts, m
     * @param fromLane  lane at the start of the stretch
     * @param toLane    lane at the end of the stretch (differs when the car changes lane)
     */
    public record Stretch(double startArc, double endArc, int link, int connector, double linkStart,
                          int fromLane, int toLane) {
    }

    private static long nextVersion = 1;

    private final long version;
    private final String destination;
    private final double[] xs;
    private final double[] ys;
    private final double[] arcs;
    private final double[] speedLimits;
    private final double[] targetSpeeds;
    private final List<Maneuver> maneuvers;
    private final List<Integer> edgeIds;
    private final List<JunctionEntry> junctions;
    private final List<Stretch> stretches;
    private final int goalEdge;
    private final double goalArc;
    private final double estimatedSeconds;

    private Route(String destination, double[] xs, double[] ys, double[] speedLimits, List<Maneuver> maneuvers,
                  List<Integer> edgeIds, List<JunctionEntry> junctions, List<Stretch> stretches, int goalEdge,
                  double goalArc) {
        synchronized (Route.class) {
            this.version = nextVersion++;
        }
        this.destination = destination;
        this.xs = xs;
        this.ys = ys;
        this.speedLimits = speedLimits;
        this.maneuvers = List.copyOf(maneuvers);
        this.edgeIds = List.copyOf(edgeIds);
        this.junctions = List.copyOf(junctions);
        this.stretches = List.copyOf(stretches);
        this.goalEdge = goalEdge;
        this.goalArc = goalArc;
        int n = xs.length;
        arcs = new double[n];
        for (int i = 1; i < n; i++) {
            arcs[i] = arcs[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        targetSpeeds = speedProfile();
        double seconds = 0;
        for (int i = 1; i < n; i++) {
            double v = Math.max(3.0, (targetSpeeds[i] + targetSpeeds[i - 1]) / 2);
            seconds += (arcs[i] - arcs[i - 1]) / v;
        }
        estimatedSeconds = seconds;
    }

    /**
     * Builds the drivable path for a list of edges, ending at the kerb.
     *
     * @param network     lanes and junction paths
     * @param edges       edges from the path finder, starting with the car's current edge
     * @param startArc    distance already driven along the first edge, m
     * @param startLane   lane the car is in now
     * @param goalArc     where on the last edge the destination is, m
     * @param destination name of the destination for directions
     */
    public static Route build(RoadNetwork network, List<RoadGraph.Edge> edges, double startArc, int startLane,
                              double goalArc, String destination) {
        return build(network, edges, startArc, startLane, goalArc, destination, -1, -1);
    }

    /**
     * Builds a route, optionally with a lane change on the first edge that starts right away (used
     * to overtake): the car moves to {@code changeToLane} starting {@code changeAt} m along the
     * first edge.
     */
    public static Route build(RoadNetwork network, List<RoadGraph.Edge> edges, double startArc, int startLane,
                              double goalArc, String destination, int changeToLane, double changeAt) {
        Builder b = new Builder(network);
        int lane = Math.max(0, Math.min(edges.get(0).link().lanes() - 1, startLane));
        for (int e = 0; e < edges.size(); e++) {
            RoadNetwork.Link link = edges.get(e).link();
            boolean last = e == edges.size() - 1;
            double from = e == 0 ? startArc : 0;
            double to = last ? Math.max(from + 1, Math.min(link.length(), goalArc)) : link.length();
            RoadNetwork.Connector connector = null;
            int exitLane;
            if (last) {
                exitLane = 0; // pull in to the left-hand kerb at the destination
            } else {
                connector = choose(network, link, edges.get(e + 1).link(), lane, e + 2 < edges.size()
                        ? edges.get(e + 2).link() : null);
                exitLane = connector == null ? lane : connector.fromLane();
            }
            int entryLane = lane;
            if (e == 0 && changeToLane >= 0 && changeToLane < link.lanes()) {
                // Deliberate lane change first (overtaking), then whatever the junction needs.
                double changeEnd = Math.min(to, changeAt + LANE_CHANGE_LENGTH);
                b.addLink(link, from, changeEnd, entryLane, changeToLane, changeAt, changeEnd, 0);
                from = changeEnd;
                entryLane = changeToLane;
            }
            b.addLink(link, from, to, entryLane, exitLane, Double.NaN, Double.NaN,
                    last ? 0 : LANE_CHANGE_DONE_BEFORE_JUNCTION);
            if (!last) {
                if (connector == null) {
                    // No turn path (should not happen for edges from the path finder): join straight.
                    lane = Math.min(exitLane, edges.get(e + 1).link().lanes() - 1);
                    continue;
                }
                b.addConnector(connector, edges.get(e + 1).roadName());
                lane = connector.toLane();
            }
        }
        b.maneuvers.add(new Maneuver(b.arc(), Maneuver.Type.ARRIVE, destination));
        List<Integer> ids = new ArrayList<>();
        for (RoadGraph.Edge edge : edges) {
            ids.add(edge.id());
        }
        return b.finish(destination, ids, edges.get(edges.size() - 1).id(), goalArc);
    }

    /** The turn path from a link to the next, from the lane nearest the current one. */
    private static RoadNetwork.Connector choose(RoadNetwork network, RoadNetwork.Link link, RoadNetwork.Link next,
                                                int currentLane, RoadNetwork.Link afterNext) {
        RoadNetwork.Connector best = null;
        double bestScore = Double.MAX_VALUE;
        for (RoadNetwork.Connector c : network.connectorsBetween(link.id(), next.id())) {
            if (c.turn() == RoadNetwork.Turn.UTURN && network.connectorsBetween(link.id(), next.id()).size() > 1) {
                continue;
            }
            // Fewest lane changes; between equals, keep left (traffic keeps left in India).
            double score = Math.abs(c.fromLane() - currentLane) + 0.1 * c.fromLane() + 0.1 * c.toLane();
            if (afterNext != null) {
                // Prefer arriving in a lane that suits the following turn too.
                List<RoadNetwork.Connector> following = network.connectorsBetween(next.id(), afterNext.id());
                double nearest = Double.MAX_VALUE;
                for (RoadNetwork.Connector f : following) {
                    nearest = Math.min(nearest, Math.abs(f.fromLane() - c.toLane()));
                }
                if (nearest < Double.MAX_VALUE) {
                    score += 0.5 * nearest;
                }
            }
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    /** Collects points, speed limits and bookkeeping while the route is laid out. */
    private static final class Builder {
        private final RoadNetwork network;
        private final List<double[]> points = new ArrayList<>(); // {x, y, speedLimit}
        private final List<Maneuver> maneuvers = new ArrayList<>();
        private final List<JunctionEntry> junctions = new ArrayList<>();
        private final List<Stretch> stretches = new ArrayList<>();
        private double arc;

        Builder(RoadNetwork network) {
            this.network = network;
        }

        double arc() {
            return arc;
        }

        private void add(double x, double y, double limit) {
            if (!points.isEmpty()) {
                double[] last = points.get(points.size() - 1);
                double d = Math.hypot(x - last[0], y - last[1]);
                if (d < 0.05) {
                    return;
                }
                arc += d;
            }
            points.add(new double[] {x, y, limit});
        }

        /**
         * Adds part of a link, moving from one lane to another. The lane change happens between
         * {@code changeFrom} and {@code changeTo} (NaN: as late as convenient, finishing
         * {@code doneBefore} m before the end).
         */
        void addLink(RoadNetwork.Link link, double from, double to, int laneIn, int laneOut, double changeFrom,
                     double changeTo, double doneBefore) {
            if (to - from < 0.05) {
                return;
            }
            double startArc = arc;
            Polyline centre = link.centre();
            double o0 = link.laneOffset(laneIn);
            double o1 = link.laneOffset(laneOut);
            double c0 = changeFrom;
            double c1 = changeTo;
            if (Double.isNaN(c0)) {
                // Get into the lane for the next junction early, as a careful driver does:
                // shortly after joining the road, finished well before the junction.
                double steps = Math.max(1, Math.abs(laneOut - laneIn));
                double latestEnd = Math.max(from, to - doneBefore);
                c0 = Math.min(from + LANE_CHANGE_START, latestEnd);
                c1 = Math.min(latestEnd, c0 + LANE_CHANGE_LENGTH * steps);
                if (c1 - c0 < 8 && laneIn != laneOut) {
                    c0 = from;
                    c1 = Math.max(from + Math.min(8, to - from), latestEnd);
                }
            }
            int n = Math.max(1, (int) Math.ceil((to - from) / 1.0));
            for (int i = 0; i <= n; i++) {
                double s = from + (to - from) * i / n;
                double t = c1 <= c0 ? (s >= c1 ? 1 : 0) : Math.max(0, Math.min(1, (s - c0) / (c1 - c0)));
                double smooth = t * t * (3 - 2 * t);
                double offset = o0 + (o1 - o0) * smooth;
                Point2 p = centre.pointAt(s);
                double h = centre.headingAt(s);
                add(p.x() - Math.sin(h) * offset, p.y() + Math.cos(h) * offset, link.speedLimit());
            }
            stretches.add(new Stretch(startArc, arc, link.id(), -1, from, laneIn, laneOut));
        }

        void addConnector(RoadNetwork.Connector c, String nextRoad) {
            RoadNetwork.Link in = network.link(c.fromLink());
            RoadNetwork.Link out = network.link(c.toLink());
            boolean signalised = network.junction(c.junction()).isSignalised();
            double entry = arc;
            double stop = Math.max(0, entry - (in.length() - in.stopArc(signalised)));
            switch (c.turn()) {
                case LEFT -> maneuvers.add(new Maneuver(entry, Maneuver.Type.LEFT, nextRoad));
                case RIGHT -> maneuvers.add(new Maneuver(entry, Maneuver.Type.RIGHT, nextRoad));
                case UTURN -> maneuvers.add(new Maneuver(entry, Maneuver.Type.UTURN, nextRoad));
                default -> { }
            }
            double limit = Math.min(in.speedLimit(), out.speedLimit());
            List<Point2> pts = c.path().points();
            for (int i = 1; i < pts.size(); i++) {
                add(pts.get(i).x(), pts.get(i).y(), limit);
            }
            stretches.add(new Stretch(entry, arc, -1, c.id(), 0, c.fromLane(), c.toLane()));
            junctions.add(new JunctionEntry(stop, entry, arc, in.id(), c.id(), c.junction()));
        }

        Route finish(String destination, List<Integer> edgeIds, int goalEdge, double goalArc) {
            List<double[]> resampled = resample(points);
            int n = resampled.size();
            double[] xs = new double[n];
            double[] ys = new double[n];
            double[] limits = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = resampled.get(i)[0];
                ys[i] = resampled.get(i)[1];
                limits[i] = resampled.get(i)[2];
            }
            return new Route(destination, xs, ys, limits, maneuvers, edgeIds, junctions, stretches, goalEdge, goalArc);
        }
    }

    // ---- Speed profile -----------------------------------------------------------------

    /** Lateral acceleration drivers accept at a speed, m/s^2 (falls as speed rises). */
    public static double comfortLateral(double speed) {
        double kmh = speed * 3.6;
        double t = Math.max(0, Math.min(1, (kmh - 20) / 80));
        return LOW_SPEED_LATERAL + (HIGH_SPEED_LATERAL - LOW_SPEED_LATERAL) * t;
    }

    /** Fastest comfortable speed through a bend of this curvature, m/s. */
    public static double cornerSpeed(double curvature, double limit) {
        if (curvature < 1e-4) {
            return limit;
        }
        double v = limit;
        for (int i = 0; i < 6; i++) {
            v = Math.min(limit, Math.sqrt(comfortLateral(v) / curvature));
        }
        return v;
    }

    private double[] speedProfile() {
        int n = xs.length;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            v[i] = cornerSpeed(curvatureAt(i), speedLimits[i]);
        }
        v[n - 1] = 0;
        for (int i = n - 2; i >= 0; i--) {
            double ds = arcs[i + 1] - arcs[i];
            v[i] = Math.min(v[i], Math.sqrt(v[i + 1] * v[i + 1] + 2 * COMFORT_DECEL * ds));
        }
        return v;
    }

    /** Menger curvature through points about 4.5 m either side. */
    private double curvatureAt(int i) {
        int span = 3;
        int a = Math.max(0, i - span);
        int c = Math.min(xs.length - 1, i + span);
        if (c - a < 2) {
            return 0;
        }
        double ax = xs[a];
        double ay = ys[a];
        double bx = xs[i];
        double by = ys[i];
        double cx = xs[c];
        double cy = ys[c];
        double cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        double ab = Math.hypot(bx - ax, by - ay);
        double bc = Math.hypot(cx - bx, cy - by);
        double ca = Math.hypot(ax - cx, ay - cy);
        double denominator = ab * bc * ca;
        return denominator < 1e-9 ? 0 : 2 * Math.abs(cross) / denominator;
    }

    /** Path curvature at a distance along the route, 1/m. */
    public double curvatureAt(double arc) {
        return curvatureAt(indexAt(arc));
    }

    // ---- Queries -----------------------------------------------------------------------

    /** Unique id: a new route (including a re-route) always gets a new version. */
    public long version() {
        return version;
    }

    public String destination() {
        return destination;
    }

    public int size() {
        return xs.length;
    }

    public double x(int i) {
        return xs[i];
    }

    public double y(int i) {
        return ys[i];
    }

    public double arc(int i) {
        return arcs[i];
    }

    /** Total length, m. */
    public double length() {
        return arcs[arcs.length - 1];
    }

    public List<Maneuver> maneuvers() {
        return maneuvers;
    }

    /** Ids of the edges the route uses, in order. */
    public List<Integer> edgeIds() {
        return edgeIds;
    }

    /** Junctions along the route, in order. */
    public List<JunctionEntry> junctions() {
        return junctions;
    }

    /** Links and junction paths along the route, in order. */
    public List<Stretch> stretches() {
        return stretches;
    }

    /** The stretch at a distance along the route. */
    public Stretch stretchAt(double arc) {
        for (Stretch s : stretches) {
            if (arc < s.endArc()) {
                return s;
            }
        }
        return stretches.get(stretches.size() - 1);
    }

    /** Edge the destination is on. */
    public int goalEdge() {
        return goalEdge;
    }

    /** Where on the goal edge the destination is, m. */
    public double goalArc() {
        return goalArc;
    }

    /** Travel time estimate for the whole route, s. */
    public double estimatedSeconds() {
        return estimatedSeconds;
    }

    /** Planned speed at a distance along the route, m/s. */
    public double targetSpeedAt(double arc) {
        return interpolate(targetSpeeds, arc);
    }

    /** Road speed limit at a distance along the route, m/s. */
    public double speedLimitAt(double arc) {
        return speedLimits[indexAt(arc)];
    }

    /** Point at a distance along the route. */
    public Point2 pointAt(double arc) {
        int i = indexAt(arc);
        if (i >= xs.length - 1) {
            return new Point2(xs[xs.length - 1], ys[ys.length - 1]);
        }
        double t = (arc - arcs[i]) / Math.max(1e-9, arcs[i + 1] - arcs[i]);
        t = Math.max(0, Math.min(1, t));
        return new Point2(xs[i] + (xs[i + 1] - xs[i]) * t, ys[i] + (ys[i + 1] - ys[i]) * t);
    }

    /** Direction of the path at a distance along the route, rad. */
    public double headingAt(double arc) {
        int i = Math.min(indexAt(arc), xs.length - 2);
        return Math.atan2(ys[i + 1] - ys[i], xs[i + 1] - xs[i]);
    }

    /** Remaining time from a distance along the route, following the speed profile, s. */
    public double remainingSeconds(double arc) {
        double seconds = 0;
        for (int i = Math.max(1, indexAt(arc) + 1); i < xs.length; i++) {
            double v = Math.max(3.0, (targetSpeeds[i] + targetSpeeds[i - 1]) / 2);
            seconds += (arcs[i] - arcs[i - 1]) / v;
        }
        return seconds;
    }

    /**
     * Closest point on the route to (x, y), searching near a previous result so the car can
     * never "jump" to a later part of the route that happens to pass nearby.
     *
     * @return {arc, lateral distance, index}
     */
    public double[] project(double x, double y, int hintIndex) {
        int from = Math.max(0, hintIndex - 20);
        int to = Math.min(xs.length - 1, hintIndex + 80);
        double bestDistance = Double.MAX_VALUE;
        double bestArc = 0;
        int bestIndex = hintIndex;
        for (int i = from; i < to; i++) {
            double sx = xs[i + 1] - xs[i];
            double sy = ys[i + 1] - ys[i];
            double len2 = sx * sx + sy * sy;
            double t = len2 < 1e-12 ? 0 : ((x - xs[i]) * sx + (y - ys[i]) * sy) / len2;
            t = Math.max(0, Math.min(1, t));
            double px = xs[i] + sx * t;
            double py = ys[i] + sy * t;
            double d = Math.hypot(x - px, y - py);
            if (d < bestDistance) {
                bestDistance = d;
                bestArc = arcs[i] + t * Math.sqrt(len2);
                bestIndex = i;
            }
        }
        return new double[] {bestArc, bestDistance, bestIndex};
    }

    private int indexAt(double arc) {
        if (arc <= 0) {
            return 0;
        }
        int lo = 0;
        int hi = arcs.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (arcs[mid] <= arc) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private double interpolate(double[] values, double arc) {
        int i = indexAt(arc);
        if (i >= values.length - 1) {
            return values[values.length - 1];
        }
        double t = (arc - arcs[i]) / Math.max(1e-9, arcs[i + 1] - arcs[i]);
        t = Math.max(0, Math.min(1, t));
        return values[i] + (values[i + 1] - values[i]) * t;
    }

    private static List<double[]> resample(List<double[]> points) {
        List<double[]> result = new ArrayList<>();
        result.add(points.get(0));
        double carry = 0;
        for (int i = 1; i < points.size(); i++) {
            double[] a = points.get(i - 1);
            double[] b = points.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]);
            double position = SPACING - carry;
            while (position <= len) {
                double t = position / len;
                result.add(new double[] {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, t < 0.5 ? a[2] : b[2]});
                position += SPACING;
            }
            carry = len - (position - SPACING);
        }
        double[] last = points.get(points.size() - 1);
        double[] lastSampled = result.get(result.size() - 1);
        if (Math.hypot(last[0] - lastSampled[0], last[1] - lastSampled[1]) > 0.1) {
            result.add(last);
        }
        if (result.size() < 2) {
            result.add(new double[] {last[0] + 0.1, last[1], last[2]});
        }
        return result;
    }
}
