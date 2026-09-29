package com.selfdriving.autopilot;

import java.util.List;

import com.selfdriving.navigation.Route;
import com.selfdriving.physics.CarBody;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.RoadNetwork;

/**
 * Decides whether the car may enter the next junction on its route.
 *
 * <ul>
 *   <li><b>Traffic lights:</b> stop at the stop line on red; on amber stop if that is possible
 *       at a comfortable deceleration, otherwise continue (stopping hard in the junction is the
 *       more dangerous choice).</li>
 *   <li><b>Giving way:</b> where the car's path through the junction crosses a path that has
 *       priority, it waits while a vehicle it can see is on that path before the crossing
 *       point, or will get there within a safe time gap (3 s) of the car.</li>
 *   <li>If everyone is waiting for everyone (nothing has moved for a while), it goes carefully.</li>
 * </ul>
 * Uses only what the sensors report plus the map and the light's state, as a real car does
 * with its cameras.
 */
public final class JunctionPlanner {

    /** Where to stop, and why. */
    public record Decision(double stopArc, String reason, RoadNetwork.Signal signal, double signalDistance) {

        static Decision go(RoadNetwork.Signal signal, double distance) {
            return new Decision(Double.POSITIVE_INFINITY, null, signal, distance);
        }

        public boolean mustStop() {
            return !Double.isInfinite(stopArc);
        }
    }

    private static final double LOOK_AHEAD = 120;
    private static final double COMFORT_STOP = 2.6;
    private static final double GAP = 3.0;
    private static final double ON_PATH = 1.8;
    private static final double APPROACH_RANGE = 70;
    private static final double DEADLOCK = 8;

    private final RoadNetwork network;
    private double waited;
    private int waitingAt = -1;

    public JunctionPlanner(RoadNetwork network) {
        this.network = network;
    }

    public void reset() {
        waited = 0;
        waitingAt = -1;
    }

    /**
     * @param route   route being driven
     * @param arc     car's progress, m
     * @param speed   car speed, m/s
     * @param time    simulation time (for the signal plans), s
     * @param objects what the sensors see
     */
    public Decision check(Route route, double arc, double speed, double time, double dt,
                          List<SensorReadings.DetectedObject> objects) {
        double front = arc + CarBody.FRONT;
        for (Route.JunctionEntry j : route.junctions()) {
            if (j.exitArc() < arc) {
                continue;
            }
            if (j.entryArc() - arc > LOOK_AHEAD) {
                break;
            }
            RoadNetwork.Link approach = network.link(j.link());
            RoadNetwork.Signal signal = network.signal(approach, time);
            double toStop = j.stopArc() - front;
            if (toStop < -0.5) {
                continue; // past the stop line: carry on through (the lead-vehicle check still applies)
            }
            String reason = null;
            if (signal == RoadNetwork.Signal.RED) {
                reason = "Stopping for red light";
            } else if (signal == RoadNetwork.Signal.AMBER && speed * speed / (2 * COMFORT_STOP) < toStop) {
                reason = "Stopping for amber light";
            } else if (mustGiveWay(j, arc, speed, time, objects)) {
                reason = "Giving way";
            }
            if (reason != null && reason.equals("Giving way")) {
                if (waitingAt == j.junction() && speed < 0.3) {
                    waited += dt;
                } else if (waitingAt != j.junction()) {
                    waitingAt = j.junction();
                    waited = 0;
                }
                if (waited > DEADLOCK && !anyoneMoving(j, objects)) {
                    reason = null; // nobody is going: edge out carefully
                }
            }
            if (reason != null) {
                return new Decision(j.stopArc(), reason, signal, toStop);
            }
            return Decision.go(signal, toStop);
        }
        return Decision.go(RoadNetwork.Signal.NONE, Double.POSITIVE_INFINITY);
    }

    private boolean mustGiveWay(Route.JunctionEntry j, double arc, double speed, double time,
                                List<SensorReadings.DetectedObject> objects) {
        RoadNetwork.Connector mine = network.connector(j.connector());
        for (RoadNetwork.Conflict conflict : mine.conflicts()) {
            RoadNetwork.Connector other = network.connector(conflict.other());
            double myTime = (j.entryArc() + conflict.arc() - arc) / Math.max(speed, 3.0);
            RoadNetwork.Link approach = network.link(other.fromLink());
            boolean otherStopped = network.signal(approach, time) == RoadNetwork.Signal.RED;
            for (SensorReadings.DetectedObject o : objects) {
                if (!o.kind().isVehicle()) {
                    continue;
                }
                double v = Math.hypot(o.vx(), o.vy());
                // Already on that path, before or at the crossing point: always wait for it.
                Polyline.Projection onPath = other.path().project(o.x(), o.y());
                if (onPath.distance() < ON_PATH && headingMatches(o, onPath.heading())) {
                    double ahead = conflict.otherArc() - onPath.arc();
                    if (ahead > -o.halfLength() - 2) {
                        return true;
                    }
                    continue;
                }
                if (!conflict.giveWay() || otherStopped || v < 1.0) {
                    continue;
                }
                // Approaching that path with priority.
                Polyline.Projection onApproach = approach.lane(other.fromLane()).project(o.x(), o.y());
                if (onApproach.distance() > ON_PATH || !headingMatches(o, onApproach.heading())) {
                    continue;
                }
                double distance = approach.length() - onApproach.arc() + conflict.otherArc();
                if (distance < APPROACH_RANGE && distance / v < myTime + GAP) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean anyoneMoving(Route.JunctionEntry j, List<SensorReadings.DetectedObject> objects) {
        Point2 centre = network.junction(j.junction()).position();
        for (SensorReadings.DetectedObject o : objects) {
            if (o.kind().isVehicle() && Math.hypot(o.vx(), o.vy()) > 0.5
                    && Math.hypot(o.x() - centre.x(), o.y() - centre.y()) < 25) {
                return true;
            }
        }
        return false;
    }

    private static boolean headingMatches(SensorReadings.DetectedObject o, double heading) {
        double d = Math.atan2(Math.sin(o.heading() - heading), Math.cos(o.heading() - heading));
        return Math.abs(d) < Math.toRadians(50);
    }
}
