package com.selfdriving.autopilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.ParkingArea;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.RoadNetwork;
import com.selfdriving.world.Walls;

/**
 * Finds a free parking space on the left of the car and plans how to get into it.
 *
 * <p>Paths are made of straight lines and circular arcs for the <em>rear axle</em>, the point a
 * car turns about (kinematic bicycle model): driving a distance {@code s} with steering
 * curvature {@code k = tan(delta) / L} turns the car by {@code k s}. Reversing is simply a
 * negative {@code s}.
 *
 * <ul>
 *   <li><b>Parallel parking</b> (the classic two-arc manoeuvre): drive level with the space,
 *       reverse on full left steering until the car is at angle {@code theta}, then on right
 *       steering until straight. With turning radius R the car moves sideways by
 *       {@code D = 2R(1 - cos theta)} and back by {@code 2R sin theta}, so theta follows from the
 *       sideways distance to the space.</li>
 *   <li><b>Reversing into a bay:</b> drive past the bay, then reverse through a quarter circle
 *       and straight back in, ending nose-out.</li>
 * </ul>
 * A space counts as free when no wall (map) and nothing the sensors see overlaps it.
 */
public final class ParkingPlanner {

    /** One piece of the path. */
    public record Segment(double curvature, double length) {

        /** Forward (+1) or reverse (-1). */
        public int direction() {
            return length >= 0 ? 1 : -1;
        }
    }

    /** A rear-axle pose. */
    public record Pose(double x, double y, double heading) {
    }

    /**
     * A plan.
     *
     * @param kind     "parallel" or "bay"
     * @param start    rear-axle pose where the manoeuvre starts
     * @param segments pieces after the start (the approach to the start is added by the controller)
     * @param space    the space being parked in
     */
    public record Plan(String kind, Pose start, List<Segment> segments, OrientedBox space) {
    }

    /** Turning radius used for the manoeuvre (the car can do about 4.6 m), m. */
    public static final double RADIUS = 5.5;

    private static final double SPACE_LENGTH = 6.2;
    private static final double SPACE_WIDTH = 2.1;
    /** Space centre outside the road's left edge, m. */
    private static final double KERB_OFFSET = 1.1;
    private static final double SEARCH_BEHIND = 4;
    private static final double SEARCH_AHEAD = 45;
    private static final double JUNCTION_CLEARANCE = 12;

    private final RoadNetwork network;
    private final Walls walls;
    private final List<ParkingArea.Bay> bays;
    private final double cgToRear;

    public ParkingPlanner(RoadNetwork network, Walls walls, List<ParkingArea.Bay> bays, VehicleParams params) {
        this.network = network;
        this.walls = walls;
        this.bays = bays;
        this.cgToRear = params.cgToRearAxle();
    }

    /** Rear-axle pose of a car whose centre of gravity is at (x, y). */
    public Pose rearAxle(double x, double y, double heading) {
        return new Pose(x - Math.cos(heading) * cgToRear, y - Math.sin(heading) * cgToRear, heading);
    }

    /** Centre-of-gravity position for a rear-axle pose: {x, y}. */
    public double[] centre(Pose rear) {
        return new double[] {rear.x() + Math.cos(rear.heading()) * cgToRear,
                rear.y() + Math.sin(rear.heading()) * cgToRear};
    }

    /**
     * Looks for a free bay nearby on the left, then for a space along the kerb.
     *
     * @param x       car CG east
     * @param y       car CG north
     * @param heading car heading
     * @param objects what the sensors see
     * @param statics static obstacles known to be there (parked cars already detected count too)
     */
    public Optional<Plan> plan(double x, double y, double heading, List<SensorReadings.DetectedObject> objects) {
        RoadNetwork.Position here = network.locate(x, y, heading, 2.5, Math.toRadians(35));
        if (here == null) {
            return Optional.empty();
        }
        RoadNetwork.Link link = here.link();
        Polyline lane = link.lane(here.lane());
        double arc = lane.project(x, y).arc();
        Optional<Plan> bay = planBay(link, lane, arc, objects);
        if (bay.isPresent()) {
            return bay;
        }
        if (here.lane() != 0) {
            return Optional.empty(); // parallel parking needs the kerb lane
        }
        return planParallel(link, lane, arc, objects);
    }

    // ---- parallel ------------------------------------------------------------------------------

    private Optional<Plan> planParallel(RoadNetwork.Link link, Polyline lane, double carArc,
                                        List<SensorReadings.DetectedObject> objects) {
        Polyline centre = link.centre();
        double sideways = link.leftEdge() + KERB_OFFSET - link.laneOffset(0);
        double cos = 1 - sideways / (2 * RADIUS);
        if (cos < 0) {
            return Optional.empty();
        }
        double theta = Math.acos(cos);
        double back = 2 * RADIUS * Math.sin(theta);
        for (double s = carArc - SEARCH_BEHIND; s <= carArc + SEARCH_AHEAD; s += 0.5) {
            double spaceArc = s + SPACE_LENGTH / 2;
            if (s < JUNCTION_CLEARANCE || spaceArc + SPACE_LENGTH / 2 > link.length() - JUNCTION_CLEARANCE) {
                continue;
            }
            Point2 c = centre.pointAt(spaceArc);
            double h = centre.headingAt(spaceArc);
            double off = link.leftEdge() + KERB_OFFSET;
            OrientedBox space = new OrientedBox(c.x() - Math.sin(h) * off, c.y() + Math.cos(h) * off, h,
                    SPACE_LENGTH / 2, SPACE_WIDTH / 2);
            if (!free(space, objects)) {
                continue;
            }
            // Final pose: car body centred in the space.
            double bodyCentreToRear = CarBody.centreOffset() + cgToRear;
            Pose goal = new Pose(space.cx() - Math.cos(h) * bodyCentreToRear, space.cy() - Math.sin(h) * bodyCentreToRear, h);
            List<Segment> segments = List.of(new Segment(1 / RADIUS, -RADIUS * theta),
                    new Segment(-1 / RADIUS, -RADIUS * theta));
            Pose start = backwards(goal, segments);
            // The start must be in the lane, level with or ahead of where the car can drive to.
            double startArc = lane.project(start.x(), start.y()).arc();
            if (startArc < carArc - 1 || back <= 0) {
                continue;
            }
            // The swing of the front corner must not hit anything either.
            if (!sweptClear(start, segments, objects)) {
                continue;
            }
            return Optional.of(new Plan("parallel", start, segments, space));
        }
        return Optional.empty();
    }

    // ---- bay ----------------------------------------------------------------------------------

    private Optional<Plan> planBay(RoadNetwork.Link link, Polyline lane, double carArc,
                                   List<SensorReadings.DetectedObject> objects) {
        for (ParkingArea.Bay bay : bays) {
            Polyline.Projection p = lane.project(bay.centre().x(), bay.centre().y());
            double bayArc = p.arc();
            if (bayArc < carArc - 3 || bayArc > carArc + SEARCH_AHEAD || p.distance() > 12) {
                continue;
            }
            // Bay on the left?
            Point2 at = lane.pointAt(bayArc);
            double h = lane.headingAt(bayArc);
            double side = -(bay.centre().x() - at.x()) * Math.sin(h) + (bay.centre().y() - at.y()) * Math.cos(h);
            if (side <= 0) {
                continue;
            }
            OrientedBox space = new OrientedBox(bay.centre().x(), bay.centre().y(), bay.heading(),
                    bay.depth() / 2 - 0.1, bay.width() / 2 - 0.15);
            if (!free(space, objects)) {
                continue;
            }
            double bodyCentreToRear = CarBody.centreOffset() + cgToRear;
            double hb = bay.heading();
            Pose goal = new Pose(bay.centre().x() - Math.cos(hb) * bodyCentreToRear,
                    bay.centre().y() - Math.sin(hb) * bodyCentreToRear, hb);
            // Quarter turn reversing, then straight back; choose the straight so the start is in the lane.
            double turn = Math.atan2(Math.sin(h - hb), Math.cos(h - hb)); // positive: bay is to the left
            Pose s0 = backwards(goal, bayPath(turn, 0));
            Pose s1 = backwards(goal, bayPath(turn, 1));
            double o0 = lateral(lane, bayArc, s0);
            double o1 = lateral(lane, bayArc, s1);
            if (Math.abs(o1 - o0) < 1e-6) {
                continue;
            }
            double straight = -o0 / (o1 - o0);
            if (straight < 0) {
                continue;
            }
            List<Segment> segments = bayPath(turn, straight);
            Pose start = backwards(goal, segments);
            if (lane.project(start.x(), start.y()).arc() < carArc - 1) {
                continue;
            }
            return Optional.of(new Plan("bay", start, segments, space));
        }
        return Optional.empty();
    }

    private static List<Segment> bayPath(double turn, double straight) {
        return List.of(new Segment(1 / RADIUS, -RADIUS * Math.abs(turn)), new Segment(0, -straight));
    }

    /** Sideways offset of a pose from the lane centre (positive = left). */
    private static double lateral(Polyline lane, double arcHint, Pose p) {
        Polyline.Projection q = lane.project(p.x(), p.y());
        Point2 on = lane.pointAt(q.arc());
        double h = lane.headingAt(q.arc());
        return -(p.x() - on.x()) * Math.sin(h) + (p.y() - on.y()) * Math.cos(h);
    }

    // ---- geometry -----------------------------------------------------------------------------

    /** Moves a rear-axle pose along a segment (or part of one). */
    public static Pose advance(Pose p, double curvature, double s) {
        if (Math.abs(curvature) < 1e-9) {
            return new Pose(p.x() + Math.cos(p.heading()) * s, p.y() + Math.sin(p.heading()) * s, p.heading());
        }
        double h1 = p.heading() + curvature * s;
        double r = 1 / curvature;
        return new Pose(p.x() + r * (Math.sin(h1) - Math.sin(p.heading())),
                p.y() - r * (Math.cos(h1) - Math.cos(p.heading())), h1);
    }

    /** The pose from which driving the segments ends at {@code goal}. */
    public static Pose backwards(Pose goal, List<Segment> segments) {
        Pose p = goal;
        for (int i = segments.size() - 1; i >= 0; i--) {
            p = advance(p, segments.get(i).curvature(), -segments.get(i).length());
        }
        return p;
    }

    private boolean free(OrientedBox space, List<SensorReadings.DetectedObject> objects) {
        if (walls.contact(space, null) != null) {
            return false;
        }
        for (SensorReadings.DetectedObject o : objects) {
            OrientedBox b = new OrientedBox(o.x(), o.y(), o.heading(), o.halfLength() + 0.2, o.halfWidth() + 0.2);
            if (space.overlap(b) != null) {
                return false;
            }
        }
        return true;
    }

    /** The car's footprint along the manoeuvre stays clear of walls and objects. */
    private boolean sweptClear(Pose start, List<Segment> segments, List<SensorReadings.DetectedObject> objects) {
        Pose p = start;
        for (Segment seg : segments) {
            int steps = (int) Math.ceil(Math.abs(seg.length()) / 0.5);
            for (int i = 1; i <= steps; i++) {
                Pose q = advance(p, seg.curvature(), seg.length() * i / steps);
                double[] cg = centre(q);
                OrientedBox body = new OrientedBox(cg[0] + Math.cos(q.heading()) * CarBody.centreOffset(),
                        cg[1] + Math.sin(q.heading()) * CarBody.centreOffset(), q.heading(), CarBody.halfLength() + 0.1,
                        CarBody.HALF_WIDTH + 0.1);
                if (walls.contact(body, null) != null) {
                    return false;
                }
                for (SensorReadings.DetectedObject o : objects) {
                    if (body.overlap(new OrientedBox(o.x(), o.y(), o.heading(), o.halfLength(), o.halfWidth())) != null) {
                        return false;
                    }
                }
            }
            p = advance(p, seg.curvature(), seg.length());
        }
        return true;
    }

    /** Samples a manoeuvre as rear-axle poses every 0.25 m (for drawing and tracking). */
    public static List<Pose> sample(Pose start, Segment segment) {
        List<Pose> result = new ArrayList<>();
        int steps = Math.max(1, (int) Math.ceil(Math.abs(segment.length()) / 0.25));
        for (int i = 0; i <= steps; i++) {
            result.add(advance(start, segment.curvature(), segment.length() * i / steps));
        }
        return result;
    }
}
