package com.selfdriving.autopilot;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.navigation.RoadGraph;
import com.selfdriving.navigation.Route;
import com.selfdriving.navigation.RouteTracker;
import com.selfdriving.physics.CarBody;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.world.Point2;
import com.selfdriving.world.RoadNetwork;

/**
 * Lane changes the route does not plan by itself, decided from what the sensors see:
 *
 * <ul>
 *   <li><b>Overtaking.</b> Stuck behind a slow vehicle for a few seconds on a road with a free
 *       lane to the right (traffic keeps left, so overtaking is on the right), with enough road
 *       left before the next junction: move over, pass, and the route brings the car back into
 *       the lane it needs.</li>
 *   <li><b>Keeping left.</b> In an overtaking lane with nothing to pass: move back left when that
 *       lane is clear.</li>
 *   <li><b>Blind spot.</b> A planned lane change is put off while a vehicle is alongside or
 *       closing fast in the target lane.</li>
 * </ul>
 * A change is made by planning the route again from where the car is with a lane change
 * starting a few metres ahead, so steering, speeds and indicators all follow the new path.
 */
public final class LaneChangePlanner {

    /** What to do. */
    public record Change(int toLane, double atArc, String reason) {
    }

    private static final double SLOW_SHARE = 0.75;
    private static final double SLOW_TIME = 2.5;
    private static final double FOLLOW_RANGE = 45;
    private static final double ROOM_NEEDED = 110;
    private static final double REAR_CHECK = 30;
    private static final double FRONT_CHECK = 25;
    private static final double COOLDOWN = 6;
    private static final double LOOK = 25;

    private final RoadNetwork network;
    private double slowFor;
    private double cooldown;

    public LaneChangePlanner(RoadNetwork network) {
        this.network = network;
    }

    public void reset() {
        slowFor = 0;
        cooldown = 0;
    }

    /**
     * @param wanted the speed the car would drive at with nothing in the way, m/s
     * @param lead   what the autopilot is following (null for nothing)
     * @return a change to make now, or null
     */
    public Change decide(double dt, RouteTracker tracker, double x, double y, double speed, double wanted,
                         SensorReadings.DetectedObject lead, List<SensorReadings.DetectedObject> objects) {
        cooldown = Math.max(0, cooldown - dt);
        Route route = tracker.route();
        double arc = tracker.arc();
        Route.Stretch stretch = route.stretchAt(arc);
        if (stretch.link() < 0) {
            slowFor = 0;
            return null; // never inside a junction
        }
        RoadNetwork.Link link = network.link(stretch.link());
        int lane = currentLane(route, arc, stretch);
        double toJunction = stretch.endArc() - arc;

        // 1. A planned change into a lane that is occupied: put it off.
        int planned = route.laneChangeAt(arc, 8);
        if (planned != 0 && cooldown == 0 && arc < stretch.changeStart() + 5) {
            int target = lane + planned;
            if (target >= 0 && target < link.lanes() && !laneClear(route, arc, speed, link, target, objects)
                    && toJunction > 70) {
                cooldown = 2;
                return new Change(lane, arc, "Waiting for a gap to change lane");
            }
        }

        // 2. Overtake a slow vehicle.
        double leadSpeed = lead == null ? 0 : speedAlong(lead, route.headingAt(arc));
        // Only moving vehicles are overtaken: a stopped one is usually a queue at a light.
        boolean slowLead = lead != null && lead.kind().isVehicle() && leadSpeed > 1.0
                && leadSpeed < wanted * SLOW_SHARE && wanted > 5
                && lead.distance() < FOLLOW_RANGE;
        slowFor = slowLead ? slowFor + dt : 0;
        if (slowFor > SLOW_TIME && cooldown == 0 && planned == 0 && lane + 1 < link.lanes()
                && toJunction > ROOM_NEEDED && Double.isNaN(stretch.changeStart())) {
            if (laneClear(route, arc, speed, link, lane + 1, objects)) {
                cooldown = COOLDOWN;
                slowFor = 0;
                return new Change(lane + 1, arc + 3, "Overtaking");
            }
        }

        // 3. Keep left when there is nothing to overtake.
        if (!slowLead && cooldown == 0 && planned == 0 && lane > 0 && Double.isNaN(stretch.changeStart())
                && toJunction > ROOM_NEEDED && leftLaneWorks(route, arc, link, lane - 1)
                && laneClear(route, arc, speed, link, lane - 1, objects)) {
            cooldown = COOLDOWN;
            return new Change(lane - 1, arc + 3, "Moving back to the left lane");
        }
        return null;
    }

    /** Whether the next turn on the route can also be made from a lane (so moving there is no detour). */
    private boolean leftLaneWorks(Route route, double arc, RoadNetwork.Link link, int lane) {
        for (Route.Stretch s : route.stretches()) {
            if (s.startArc() > arc && s.connector() >= 0) {
                int next = network.connector(s.connector()).toLink();
                return network.connectorsBetween(link.id(), next).stream().anyMatch(c -> c.fromLane() == lane);
            }
        }
        return true; // last road: the car pulls in to the kerb anyway
    }

    /** The lane the car is in now, from the stretch. */
    private static int currentLane(Route route, double arc, Route.Stretch s) {
        if (Double.isNaN(s.changeStart()) || arc < s.changeStart()) {
            return s.fromLane();
        }
        return arc >= s.changeEnd() ? s.toLane() : s.fromLane();
    }

    /** Nothing alongside, just ahead, or closing fast from behind in a lane. */
    private boolean laneClear(Route route, double arc, double speed, RoadNetwork.Link link, int lane,
                              List<SensorReadings.DetectedObject> objects) {
        double heading = route.headingAt(arc);
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        Point2 here = route.pointAt(arc);
        RoadNetwork.Position me = network.locate(here.x(), here.y(), heading, 4, Math.toRadians(60));
        double myOffset = me == null ? link.laneOffset(Math.max(0, lane - 1)) : me.offset();
        double targetSide = link.laneOffset(lane) - myOffset;
        for (SensorReadings.DetectedObject o : objects) {
            double dx = o.x() - here.x();
            double dy = o.y() - here.y();
            double ahead = dx * c + dy * s;
            double side = -dx * s + dy * c;
            if (Math.abs(side - targetSide) > link.laneWidth() / 2 + o.halfWidth()) {
                continue;
            }
            double v = speedAlong(o, heading);
            double closing = v - speed;
            double rearWindow = REAR_CHECK + Math.max(0, closing) * 3;
            if (ahead > -rearWindow - o.halfLength() && ahead < FRONT_CHECK + o.halfLength() + CarBody.FRONT) {
                return false;
            }
        }
        return true;
    }

    private static double speedAlong(SensorReadings.DetectedObject o, double heading) {
        return o.vx() * Math.cos(heading) + o.vy() * Math.sin(heading);
    }

    /**
     * Plans the route again from the car with the change.
     *
     * @return the new route, or null if the car cannot be placed on the network
     */
    public Route apply(Change change, RoadGraph graph, Route route, double x, double y, double heading) {
        var here = graph.locate(x, y, heading);
        if (here.isEmpty()) {
            return null;
        }
        RoadGraph.Location l = here.get();
        List<Integer> ids = route.edgeIds();
        int from = ids.indexOf(l.edge().id());
        if (from < 0) {
            return null;
        }
        List<RoadGraph.Edge> edges = new ArrayList<>();
        for (int i = from; i < ids.size(); i++) {
            edges.add(graph.edge(ids.get(i)));
        }
        if (change.toLane() == l.lane()) {
            // Put a planned change off: stay in this lane for a while, then plan as usual.
            return Route.build(network, edges, l.arc(), l.lane(), route.goalArc(), route.destination(), l.lane(),
                    l.arc() + LOOK - 40);
        }
        return Route.build(network, edges, l.arc(), l.lane(), route.goalArc(), route.destination(), change.toLane(),
                l.arc() + 3);
    }
}
