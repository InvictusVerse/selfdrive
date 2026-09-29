package com.selfdriving.navigation;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;

/**
 * A planned drive: the exact line to follow (lane centres joined by rounded corners), a speed
 * for every point, and the turn-by-turn directions.
 *
 * <p>The path is resampled every {@link #SPACING} m. The speed profile respects each road's limit
 * and a comfortable cornering acceleration ({@code v = sqrt(a / curvature)}), then a backward pass
 * makes sure the car can always brake comfortably in time for the next corner and for the stop at
 * the destination ({@code v^2 = v_next^2 + 2 a d}).
 *
 * <p>Immutable, so it can be shared with the display.
 */
public final class Route {

    /** Distance between path points, m. */
    public static final double SPACING = 1.5;

    /** Comfortable cornering acceleration used for the speed profile, m/s^2. */
    public static final double COMFORT_LATERAL_ACCEL = 2.5;

    /** Comfortable braking used for the speed profile, m/s^2. */
    public static final double COMFORT_DECEL = 2.0;

    private static final double CORNER_TRIM = 7.0;
    private static final double TURN_THRESHOLD = Math.toRadians(30);

    /** A direction for the driver, placed where the manoeuvre starts. */
    public record Maneuver(double arc, Type type, String road) {

        public enum Type { LEFT, RIGHT, ARRIVE }

        /** Short instruction, e.g. "Turn left onto Park Street". */
        public String instruction() {
            return switch (type) {
                case LEFT -> "Turn left onto " + road;
                case RIGHT -> "Turn right onto " + road;
                case ARRIVE -> "Arrive at " + road;
            };
        }
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
    private final double estimatedSeconds;

    private Route(String destination, double[] xs, double[] ys, double[] speedLimits, List<Maneuver> maneuvers,
                  List<Integer> edgeIds) {
        synchronized (Route.class) {
            this.version = nextVersion++;
        }
        this.destination = destination;
        this.xs = xs;
        this.ys = ys;
        this.speedLimits = speedLimits;
        this.maneuvers = List.copyOf(maneuvers);
        this.edgeIds = List.copyOf(edgeIds);
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
     * Builds the drivable path for a list of edges.
     *
     * @param edges       edges from the path finder, starting with the car's current edge
     * @param startArc    distance already driven along the first edge, m
     * @param destination name of the destination for directions
     */
    public static Route build(List<RoadGraph.Edge> edges, double startArc, String destination) {
        List<double[]> points = new ArrayList<>(); // {x, y, speedLimit}
        List<Maneuver> maneuvers = new ArrayList<>();
        List<Integer> edgeIds = new ArrayList<>();
        double arc = 0;

        for (int e = 0; e < edges.size(); e++) {
            RoadGraph.Edge edge = edges.get(e);
            edgeIds.add(edge.id());
            List<Point2> lane = edge.lane().points();
            double from = e == 0 ? startArc : CORNER_TRIM;
            // Never start the corner behind the car when it is already close to the junction.
            double to = Math.max(from, edge.length() - (e < edges.size() - 1 ? CORNER_TRIM : 0));
            List<Point2> piece = slice(lane, from, to);
            for (Point2 p : piece) {
                if (!points.isEmpty()) {
                    double[] last = points.get(points.size() - 1);
                    arc += Math.hypot(p.x() - last[0], p.y() - last[1]);
                }
                points.add(new double[] {p.x(), p.y(), edge.speedLimit()});
            }

            if (e < edges.size() - 1) {
                RoadGraph.Edge next = edges.get(e + 1);
                // Rounded corner: quadratic Bezier from the end of this lane to the start of the next,
                // with its control point where the two lanes would meet.
                Point2 a = pointAt(lane, to);
                Point2 b = pointAt(next.lane().points(), CORNER_TRIM);
                Point2 aEnd = lane.get(lane.size() - 1);
                Point2 bStart = next.lane().points().get(0);
                double inHeading = headingNear(lane, edge.length(), true);
                double outHeading = headingNear(next.lane().points(), 0, false);
                Point2 control = intersection(aEnd, inHeading, bStart, outHeading)
                        .orElse(new Point2((aEnd.x() + bStart.x()) / 2, (aEnd.y() + bStart.y()) / 2));
                double turn = Math.atan2(Math.sin(outHeading - inHeading), Math.cos(outHeading - inHeading));
                if (Math.abs(turn) > TURN_THRESHOLD) {
                    maneuvers.add(new Maneuver(arc, turn > 0 ? Maneuver.Type.LEFT : Maneuver.Type.RIGHT,
                            next.roadName()));
                }
                double limit = Math.min(edge.speedLimit(), next.speedLimit());
                for (int k = 1; k < 12; k++) {
                    double t = k / 12.0;
                    double u = 1 - t;
                    double x = u * u * a.x() + 2 * u * t * control.x() + t * t * b.x();
                    double y = u * u * a.y() + 2 * u * t * control.y() + t * t * b.y();
                    double[] last = points.get(points.size() - 1);
                    arc += Math.hypot(x - last[0], y - last[1]);
                    points.add(new double[] {x, y, limit});
                }
            }
        }
        maneuvers.add(new Maneuver(arc, Maneuver.Type.ARRIVE, destination));

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
        return new Route(destination, xs, ys, limits, maneuvers, edgeIds);
    }

    // ---- Speed profile -----------------------------------------------------------------

    private double[] speedProfile() {
        int n = xs.length;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) {
            double curvature = curvatureAt(i);
            double cornering = curvature > 1e-4 ? Math.sqrt(COMFORT_LATERAL_ACCEL / curvature) : Double.MAX_VALUE;
            v[i] = Math.min(speedLimits[i], cornering);
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

    // ---- Geometry helpers --------------------------------------------------------------

    private static List<Point2> slice(List<Point2> line, double from, double to) {
        List<Point2> result = new ArrayList<>();
        result.add(pointAt(line, from));
        double travelled = 0;
        for (int i = 1; i < line.size(); i++) {
            travelled += line.get(i - 1).distanceTo(line.get(i));
            if (travelled > from && travelled < to) {
                result.add(line.get(i));
            }
        }
        result.add(pointAt(line, to));
        return result;
    }

    private static Point2 pointAt(List<Point2> line, double arc) {
        double travelled = 0;
        for (int i = 1; i < line.size(); i++) {
            Point2 a = line.get(i - 1);
            Point2 b = line.get(i);
            double len = a.distanceTo(b);
            if (travelled + len >= arc) {
                double t = len < 1e-9 ? 0 : (arc - travelled) / len;
                return new Point2(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t);
            }
            travelled += len;
        }
        return line.get(line.size() - 1);
    }

    /** Heading at the end (or start) of a line, from a point about 5 m away. */
    private static double headingNear(List<Point2> line, double arc, boolean atEnd) {
        double total = 0;
        for (int i = 1; i < line.size(); i++) {
            total += line.get(i - 1).distanceTo(line.get(i));
        }
        Point2 a = atEnd ? pointAt(line, Math.max(0, total - 5)) : pointAt(line, 0);
        Point2 b = atEnd ? pointAt(line, total) : pointAt(line, Math.min(total, 5));
        return Math.atan2(b.y() - a.y(), b.x() - a.x());
    }

    private static java.util.Optional<Point2> intersection(Point2 p, double headingP, Point2 q, double headingQ) {
        double dx1 = Math.cos(headingP);
        double dy1 = Math.sin(headingP);
        double dx2 = Math.cos(headingQ);
        double dy2 = Math.sin(headingQ);
        double denominator = dx1 * dy2 - dy1 * dx2;
        if (Math.abs(denominator) < 0.2) {
            return java.util.Optional.empty(); // nearly straight on
        }
        double t = ((q.x() - p.x()) * dy2 - (q.y() - p.y()) * dx2) / denominator;
        return java.util.Optional.of(new Point2(p.x() + dx1 * t, p.y() + dy1 * t));
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
        return result;
    }
}
