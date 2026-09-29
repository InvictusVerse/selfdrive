package com.selfdriving.traffic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.selfdriving.navigation.Route;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.RoadNetwork;

/**
 * Other road users: cars, SUVs, auto-rickshaws, motorbikes, buses and trucks driving through the
 * city on the lane network.
 *
 * <p><b>Following.</b> Speed comes from the Intelligent Driver Model (Treiber, Hennecke and
 * Helbing, 2000): {@code a = a_max [1 - (v/v0)^4 - (s_w / s)^2]} with the wanted gap
 * {@code s_w = s0 + v T + v dv / (2 sqrt(a b))}. The "vehicle ahead" can be a real one, the end
 * of a queue at a red light, or a point where the vehicle must give way.
 *
 * <p><b>Lane changes.</b> MOBIL (Kesting, Treiber and Helbing, 2007): change lane when it gains
 * acceleration and the new follower would not have to brake hard. Traffic keeps left, so moving
 * right (to overtake) needs a bigger gain than moving back left. Before a junction vehicles
 * move into a lane that allows their next turn.
 *
 * <p><b>Junctions.</b> Signals are obeyed (stop on red, and on amber when there is room to
 * stop). Where two paths cross, the one that must give way waits until the other vehicle has
 * passed or is far enough away, and nobody enters a junction path that someone is still on.
 *
 * <p>Each vehicle is also an {@link Obstacle}, so the car's sensors, autopilot, emergency braking
 * and collisions see traffic exactly like anything else. Owned by the simulation thread.
 */
public final class TrafficSystem {

    /** First obstacle id used for traffic (scenario actors and buildings use lower ids). */
    public static final int FIRST_ID = 100_000;

    private static final double IDM_DELTA = 4;
    private static final double LOOK_AHEAD = 110;
    private static final double DECISION_DISTANCE = 45;
    private static final double GAP_ACCEPTANCE = 3.0;
    private static final double LANE_CHANGE_TIME = 3.0;
    private static final double MOBIL_THRESHOLD = 0.25;
    private static final double MOBIL_RIGHT_BIAS = 0.3;
    private static final double SAFE_DECEL = 3.0;
    private static final double DEADLOCK_WAIT = 12;
    private static final double STUCK_REMOVE = 90;
    private static final double SPAWN_CLEARANCE = 25;
    private static final double SHORT_APPROACH = 8;
    /** Distance over which turn paths from the same lane overlap, m. */
    private static final double SIBLING_SHARED = 6;

    /** The driven car as traffic sees it. */
    public record Ego(double x, double y, double heading, double speed, double vx, double vy, double halfLength,
                      double halfWidth) {
    }

    /** What the display needs to draw one vehicle. */
    public record View(int id, VehicleType type, OrientedBox box, boolean braking, int indicator, boolean hazard) {
    }

    /** One vehicle. */
    private final class Vehicle {
        final int id;
        final VehicleType type;
        final Obstacle obstacle;
        final double speedFactor;
        double v;
        double accel;
        boolean onConnector;
        int link = -1;
        int connector = -1;
        int lane;
        int targetLane;
        double lc = 1;
        double arc;
        RoadNetwork.Connector plan;
        boolean committed;
        double waited;
        double stuck;
        double mobilTimer;
        double crashed;
        double born;
        boolean remove;

        Vehicle(int id, VehicleType type, double speedFactor) {
            this.id = id;
            this.type = type;
            this.speedFactor = speedFactor;
            this.obstacle = new Obstacle(id, type.kind(), new OrientedBox(0, 0, 0, type.length() / 2,
                    type.width() / 2), type.height(), type.label());
        }

        double half() {
            return type.length() / 2;
        }

        RoadNetwork.Link currentLink() {
            return network.link(link);
        }

        RoadNetwork.Connector currentConnector() {
            return network.connector(connector);
        }

        /** Lateral offset from the link reference line, including a lane change in progress. */
        double offset() {
            RoadNetwork.Link l = currentLink();
            double t = lc >= 1 ? 1 : lc * lc * (3 - 2 * lc);
            return l.laneOffset(lane) + (l.laneOffset(targetLane) - l.laneOffset(lane)) * t;
        }

        int indicator() {
            if (lc < 1) {
                return targetLane > lane ? -1 : 1; // higher lane number = to the right
            }
            if (!onConnector && plan != null && currentLink().length() - arc < 40) {
                return switch (plan.turn()) {
                    case LEFT -> 1;
                    case RIGHT, UTURN -> -1;
                    default -> 0;
                };
            }
            if (onConnector) {
                return switch (currentConnector().turn()) {
                    case LEFT -> 1;
                    case RIGHT, UTURN -> -1;
                    default -> 0;
                };
            }
            return 0;
        }
    }

    private final RoadNetwork network;
    private final Random random;
    private final List<Vehicle> vehicles = new ArrayList<>();
    private final Map<Long, List<Vehicle>> byLane = new HashMap<>();
    private final Map<Integer, List<Vehicle>> byConnector = new HashMap<>();
    private final Map<Long, List<Vehicle>> byCell = new HashMap<>();
    private final List<RoadNetwork.Link> entries = new ArrayList<>();
    private final List<RoadNetwork.Link> spawnLinks = new ArrayList<>();
    private int target;
    private int nextId = FIRST_ID;
    private double time;
    private int redLightViolations;
    private final List<String> violations = new ArrayList<>();
    private int junctionEntries;

    /**
     * @param network lanes and junctions
     * @param count   how many vehicles to keep on the roads
     * @param seed    random seed (fixed, so runs are repeatable)
     */
    public TrafficSystem(RoadNetwork network, int count, long seed) {
        this.network = network;
        this.target = count;
        this.random = new Random(seed);
        for (RoadNetwork.Link l : network.links()) {
            if (!l.isRendered() || l.name().equals("Proving Ground Road")) {
                continue;
            }
            if (network.junction(l.from()).isBoundary()) {
                entries.add(l);
            }
            if (l.length() > 30 && !network.junction(l.to()).isBoundary()) {
                spawnLinks.add(l);
            }
        }
    }

    public int targetCount() {
        return target;
    }

    public void setTargetCount(int count) {
        target = Math.max(0, count);
    }

    public int count() {
        return vehicles.size();
    }

    /** Vehicles that entered a junction on a light that had been red for over 2 s (should be 0). */
    public int redLightViolations() {
        return redLightViolations;
    }

    /** Descriptions of red-light violations (for tests). */
    public List<String> violations() {
        return List.copyOf(violations);
    }

    /** Junction crossings so far. */
    public int junctionEntries() {
        return junctionEntries;
    }

    /** Average speed of all vehicles, m/s. */
    public double averageSpeed() {
        return vehicles.stream().mapToDouble(v -> v.v).average().orElse(0);
    }

    /** Vehicles as obstacles for sensors and collisions. */
    public List<Obstacle> obstacles() {
        List<Obstacle> result = new ArrayList<>(vehicles.size());
        for (Vehicle v : vehicles) {
            result.add(v.obstacle);
        }
        return result;
    }

    /** Vehicles for drawing. */
    public List<View> views() {
        List<View> result = new ArrayList<>(vehicles.size());
        for (Vehicle v : vehicles) {
            result.add(new View(v.id, v.type, v.obstacle.box(), v.accel < -0.8 || v.v < 0.3, v.indicator(), v.crashed > 0));
        }
        return result;
    }

    /** Removes all vehicles (they come back gradually). */
    public void clear() {
        vehicles.clear();
    }

    /** A vehicle was hit by the car: it stops with its hazard lights on for a while. */
    public void hit(int id) {
        for (Vehicle v : vehicles) {
            if (v.id == id) {
                v.crashed = 20;
                v.v = 0;
            }
        }
    }

    /** Fills the roads at the start, away from the car. */
    public void populate(Ego ego) {
        // Busier on main roads, as in a real city centre.
        double[] cumulative = new double[spawnLinks.size()];
        double total = 0;
        for (int i = 0; i < cumulative.length; i++) {
            RoadNetwork.Link l = spawnLinks.get(i);
            total += l.length() * l.lanes() * (l.rank() >= 4 ? 3 : 1);
            cumulative[i] = total;
        }
        int attempts = 0;
        while (vehicles.size() < target && attempts++ < target * 20) {
            double u = random.nextDouble() * total;
            int index = java.util.Arrays.binarySearch(cumulative, u);
            index = Math.min(cumulative.length - 1, index < 0 ? -index - 1 : index);
            RoadNetwork.Link link = spawnLinks.get(index);
            int lane = random.nextInt(link.lanes());
            // Not right at a stop line: a vehicle placed there could not stop for a red light.
            double arc = 5 + random.nextDouble() * Math.max(1, link.length() - 50);
            Point2 p = link.lane(lane).pointAt(arc);
            if (ego != null && Math.hypot(p.x() - ego.x(), p.y() - ego.y()) < SPAWN_CLEARANCE) {
                continue;
            }
            if (!laneFree(link.id(), lane, arc, 14)) {
                continue;
            }
            spawn(link, lane, arc, 0.6);
        }
    }

    // ==== simulation ============================================================================

    /**
     * Advances all vehicles.
     *
     * @param dt        time step, s
     * @param time      simulation time, s
     * @param ego       the driven car
     * @param obstacles scenario objects (pedestrians, barriers) vehicles must not hit
     */
    public void update(double dt, double time, Ego ego, List<Obstacle> obstacles) {
        this.time = time;
        index();
        for (Vehicle v : vehicles) {
            double a = decide(v, dt, ego, obstacles);
            v.accel = a;
            v.v = Math.max(0, v.v + a * dt);
        }
        for (Vehicle v : vehicles) {
            advance(v, dt);
            if (v.crashed > 0) {
                v.crashed -= dt;
            }
            v.stuck = v.v < 0.2 ? v.stuck + dt : 0;
            if (v.stuck > STUCK_REMOVE && (ego == null || distance(v, ego) > 120)) {
                v.remove = true;
            }
        }
        vehicles.removeIf(v -> v.remove);
        if (vehicles.size() < target && !entries.isEmpty() && random.nextDouble() < dt * 2) {
            RoadNetwork.Link link = entries.get(random.nextInt(entries.size()));
            int lane = random.nextInt(link.lanes());
            Point2 p = link.lane(lane).pointAt(2);
            if (laneFree(link.id(), lane, 0, 18) && (ego == null || Math.hypot(p.x() - ego.x(), p.y() - ego.y()) > 40)) {
                spawn(link, lane, 1, 0.8);
            }
        }
    }

    private double distance(Vehicle v, Ego ego) {
        return Math.hypot(v.obstacle.box().cx() - ego.x(), v.obstacle.box().cy() - ego.y());
    }

    private void spawn(RoadNetwork.Link link, int lane, double arc, double speedShare) {
        VehicleType type = VehicleType.pick(random.nextDouble());
        if (type.isHeavy() && link.laneWidth() < 3.0) {
            type = VehicleType.CAR; // buses and trucks stay on the main roads
        }
        Vehicle v = new Vehicle(nextId++, type, 0.85 + random.nextDouble() * 0.25);
        v.link = link.id();
        v.lane = lane;
        v.targetLane = lane;
        v.arc = arc;
        v.v = desiredSpeed(v, link.speedLimit()) * speedShare;
        v.mobilTimer = random.nextDouble();
        v.born = time;
        choosePlan(v);
        place(v, 0);
        vehicles.add(v);
        index();
    }

    private double desiredSpeed(Vehicle v, double limit) {
        return Math.min(v.type.maxSpeed(), limit * v.speedFactor);
    }

    // ---- where everyone is -------------------------------------------------------------------

    private static long laneKey(int link, int lane) {
        return ((long) link << 8) | lane;
    }

    private void index() {
        byLane.clear();
        byConnector.clear();
        byCell.clear();
        for (Vehicle v : vehicles) {
            byCell.computeIfAbsent(cellKey(v.obstacle.box().cx(), v.obstacle.box().cy()), k -> new ArrayList<>()).add(v);
            if (v.onConnector) {
                byConnector.computeIfAbsent(v.connector, k -> new ArrayList<>()).add(v);
            } else {
                byLane.computeIfAbsent(laneKey(v.link, v.lane), k -> new ArrayList<>()).add(v);
                if (v.lc < 1 && v.targetLane != v.lane) {
                    byLane.computeIfAbsent(laneKey(v.link, v.targetLane), k -> new ArrayList<>()).add(v);
                }
            }
        }
        for (List<Vehicle> list : byLane.values()) {
            list.sort((a, b) -> Double.compare(a.arc, b.arc));
        }
        for (List<Vehicle> list : byConnector.values()) {
            list.sort((a, b) -> Double.compare(a.arc, b.arc));
        }
    }

    private boolean laneFree(int link, int lane, double arc, double clearance) {
        for (Vehicle o : byLane.getOrDefault(laneKey(link, lane), List.of())) {
            if (Math.abs(o.arc - arc) < clearance + o.half()) {
                return false;
            }
        }
        return true;
    }

    /** Gap to and speed of whatever is ahead: {gap, speed}. */
    private double[] leader(Vehicle v, int lane) {
        double best = LOOK_AHEAD;
        double speed = 0;
        if (v.onConnector) {
            RoadNetwork.Connector c = v.currentConnector();
            for (Vehicle o : byConnector.getOrDefault(c.id(), List.of())) {
                if (o != v && o.arc > v.arc) {
                    double gap = o.arc - o.half() - v.arc - v.half();
                    if (gap < best) {
                        best = gap;
                        speed = o.v;
                    }
                    break;
                }
            }
            // Paths from the same lane start on top of each other: a vehicle that has just
            // turned off along a neighbouring path is still in the way until its tail is clear.
            for (RoadNetwork.Connector sibling : network.connectorsFrom(c.fromLink())) {
                if (sibling.id() == c.id() || sibling.fromLane() != c.fromLane()) {
                    continue;
                }
                for (Vehicle o : byConnector.getOrDefault(sibling.id(), List.of())) {
                    if (o.arc > v.arc && o.arc - o.type.length() < SIBLING_SHARED) {
                        double gap = o.arc - o.half() - v.arc - v.half();
                        if (gap < best) {
                            best = gap;
                            speed = 0;
                        }
                    }
                }
            }
            if (best == LOOK_AHEAD) {
                double base = c.length() - v.arc - v.half();
                double[] next = firstInLane(c.toLink(), c.toLane(), -1e9, base);
                if (next[0] < best) {
                    best = next[0];
                    speed = next[1];
                }
            }
            return new double[] {best, speed};
        }
        double[] same = firstInLane(v.link, lane, v.arc, -v.arc - v.half(), v);
        if (same[0] < best) {
            best = same[0];
            speed = same[1];
        }
        if (v.arc > v.currentLink().length() - 30) {
            double base = v.currentLink().length() - v.arc - v.half();
            for (RoadNetwork.Connector c : network.connectorsFrom(v.link)) {
                if (c.fromLane() != lane || (v.plan != null && c.id() == v.plan.id())) {
                    continue;
                }
                for (Vehicle o : byConnector.getOrDefault(c.id(), List.of())) {
                    if (o.arc - o.type.length() < SIBLING_SHARED) {
                        double gap = base + o.arc - o.half();
                        if (gap < best) {
                            best = gap;
                            speed = 0;
                        }
                    }
                }
            }
        }
        if (same[0] >= LOOK_AHEAD && v.plan != null) {
            double base = v.currentLink().length() - v.arc - v.half();
            for (Vehicle o : byConnector.getOrDefault(v.plan.id(), List.of())) {
                double gap = base + o.arc - o.half();
                if (gap < best) {
                    best = gap;
                    speed = o.v;
                }
                break;
            }
            if (byConnector.getOrDefault(v.plan.id(), List.of()).isEmpty()) {
                double[] next = firstInLane(v.plan.toLink(), v.plan.toLane(), -1e9, base + v.plan.length());
                if (next[0] < best) {
                    best = next[0];
                    speed = next[1];
                }
            }
        }
        return new double[] {best, speed};
    }

    private double[] firstInLane(int link, int lane, double afterArc, double base) {
        return firstInLane(link, lane, afterArc, base, null);
    }

    /** First vehicle in a lane after an arc: {gap from base, speed}. */
    private double[] firstInLane(int link, int lane, double afterArc, double base, Vehicle self) {
        for (Vehicle o : byLane.getOrDefault(laneKey(link, lane), List.of())) {
            if (o != self && o.arc > afterArc) {
                return new double[] {base + o.arc - o.half(), o.v};
            }
        }
        return new double[] {LOOK_AHEAD, 0};
    }

    /** Vehicle behind an arc in a lane, or null. */
    private Vehicle follower(int link, int lane, double arc, Vehicle self) {
        Vehicle best = null;
        for (Vehicle o : byLane.getOrDefault(laneKey(link, lane), List.of())) {
            if (o != self && o.arc < arc) {
                best = o;
            }
        }
        return best;
    }

    // ---- decisions -------------------------------------------------------------------------------

    private double idm(Vehicle v, double gap, double leaderSpeed, double v0) {
        VehicleType t = v.type;
        double free = 1 - Math.pow(v.v / Math.max(0.5, v0), IDM_DELTA);
        if (gap >= LOOK_AHEAD) {
            return t.accel() * free;
        }
        double dv = v.v - leaderSpeed;
        double wanted = t.minGap() + Math.max(0, v.v * t.timeGap() + v.v * dv / (2 * Math.sqrt(t.accel() * t.decel())));
        double s = Math.max(0.1, gap);
        return t.accel() * (free - (wanted / s) * (wanted / s));
    }

    /** The acceleration for this tick, after lane choice, junction rules and anything in the way. */
    private double decide(Vehicle v, double dt, Ego ego, List<Obstacle> obstacles) {
        if (v.crashed > 0) {
            return -8;
        }
        double limit = v.onConnector
                ? Math.min(network.link(v.currentConnector().fromLink()).speedLimit(),
                network.link(v.currentConnector().toLink()).speedLimit())
                : v.currentLink().speedLimit();
        double v0 = desiredSpeed(v, limit);
        double[] lead = leader(v, v.lane);
        if (!v.onConnector && v.lc < 1) {
            double[] other = leader(v, v.targetLane);
            if (other[0] < lead[0]) {
                lead = other;
            }
        }
        double gap = lead[0];
        double leadSpeed = lead[1];

        if (!v.onConnector) {
            RoadNetwork.Link link = v.currentLink();
            laneChoice(v, dt, link);
            // Slow for the coming turn, then decide whether the junction can be entered.
            if (v.plan != null) {
                double toEntry = link.length() - v.arc - v.half();
                double turnSpeed = Route.cornerSpeed(maxCurvature(v.plan), v0);
                v0 = Math.min(v0, Math.sqrt(turnSpeed * turnSpeed + 2 * v.type.decel() * Math.max(0, toEntry)));
                double stop = junctionStop(v, dt, link, ego);
                if (stop < gap) {
                    gap = stop;
                    leadSpeed = 0;
                }
            } else {
                // Leaving the map (or the city): nothing ahead to wait for.
                gap = Math.min(gap, LOOK_AHEAD);
            }
        } else {
            double stop = conflictInside(v);
            if (stop < gap) {
                gap = stop;
                leadSpeed = 0;
            }
            RoadNetwork.Connector c = v.currentConnector();
            v0 = Math.min(v0, Route.cornerSpeed(maxCurvature(c), v0));
            // A very short road between two parts of a junction has no room for a stop line of
            // its own: stop at the end of this path instead when its light is red.
            RoadNetwork.Link next = network.link(c.toLink());
            RoadNetwork.Signal nextSignal = network.signal(next, time);
            if (nextSignal == RoadNetwork.Signal.RED || nextSignal == RoadNetwork.Signal.AMBER) {
                double toEnd = c.length() - v.arc - v.half();
                // Short links: stop at the end of this path. Otherwise look ahead to the next
                // stop line, so a light that turns red just after the turn is still respected.
                double toStop = next.length() < SHORT_APPROACH ? toEnd
                        : toEnd + next.stopArc(true);
                boolean canStop = v.v * v.v / (2 * v.type.decel() * 1.5) < toStop;
                if (toStop > 0.5 && toStop < gap && (nextSignal == RoadNetwork.Signal.RED || canStop)) {
                    gap = toStop;
                    leadSpeed = 0;
                }
            }
        }

        double[] seen = inPath(v, ego, obstacles);
        if (seen[0] < gap) {
            gap = seen[0];
            leadSpeed = seen[1];
        }
        return Math.max(-8, idm(v, gap, leadSpeed, v0));
    }

    private static double maxCurvature(RoadNetwork.Connector c) {
        Polyline p = c.path();
        List<Point2> pts = p.points();
        double max = 0;
        for (int i = 1; i + 1 < pts.size(); i++) {
            Point2 a = pts.get(i - 1);
            Point2 b = pts.get(i);
            Point2 d = pts.get(i + 1);
            double cross = (b.x() - a.x()) * (d.y() - a.y()) - (b.y() - a.y()) * (d.x() - a.x());
            double denominator = a.distanceTo(b) * b.distanceTo(d) * d.distanceTo(a);
            if (denominator > 1e-9) {
                max = Math.max(max, 2 * Math.abs(cross) / denominator);
            }
        }
        return max;
    }

    /**
     * Distance to where the vehicle must stop before the junction (red light, giving way, a
     * blocked exit), or "far" if it may go.
     */
    private double junctionStop(Vehicle v, double dt, RoadNetwork.Link link, Ego ego) {
        RoadNetwork.Junction j = network.junction(link.to());
        double stopFront = link.stopArc(j.isSignalised()) - v.arc - v.half();
        if (v.committed || stopFront > Math.max(DECISION_DISTANCE, v.v * v.v / (2 * v.type.decel()) + 15)) {
            return LOOK_AHEAD;
        }
        if (stopFront < 0.3 && v.v > 0.5) {
            v.committed = true; // past the stop line: keep going
            if (link.length() >= SHORT_APPROACH && network.signal(link, time) == RoadNetwork.Signal.RED
                    && network.signal(link, time - 1.0) == RoadNetwork.Signal.RED) {
                redLightViolations++;
                violations.add(String.format("%s #%d link %d len %.1f v %.1f waited %.1f age %.1f", v.type.label(),
                        v.id, link.id(), link.length(), v.v, v.waited, time - v.born));
            }
            return LOOK_AHEAD;
        }
        boolean mustStop = false;
        RoadNetwork.Signal signal = network.signal(link, time);
        if (signal == RoadNetwork.Signal.RED) {
            mustStop = true;
        } else if (signal == RoadNetwork.Signal.AMBER) {
            mustStop = v.v * v.v / (2 * v.type.decel() * 1.3) < stopFront;
        }
        if (!mustStop) {
            mustStop = mustGiveWay(v, v.plan, stopFront, ego) || exitBlocked(v.plan);
        }
        if (!mustStop) {
            // Keep the junction clear: do not enter if the road after it is too short to wait on
            // and its own light is red.
            RoadNetwork.Link after = network.link(v.plan.toLink());
            RoadNetwork.Signal next = network.signal(after, time);
            mustStop = after.length() < v.type.length() + 10
                    && (next == RoadNetwork.Signal.RED || next == RoadNetwork.Signal.AMBER);
        }
        if (mustStop && v.waited > DEADLOCK_WAIT && signal != RoadNetwork.Signal.RED && junctionClear(v.plan)) {
            mustStop = false; // nobody is moving: go carefully rather than wait for ever
        }
        if (mustStop) {
            if (v.v < 0.3) {
                v.waited += dt;
            }
            return Math.max(0, stopFront);
        }
        return LOOK_AHEAD;
    }

    /** Someone with right of way is on, or about to reach, a path that crosses ours. */
    private boolean mustGiveWay(Vehicle v, RoadNetwork.Connector mine, double toEntry, Ego ego) {
        double myEta = (Math.max(0, toEntry) + 0.1) / Math.max(v.v, 2.0);
        for (RoadNetwork.Conflict conflict : mine.conflicts()) {
            RoadNetwork.Connector other = network.connector(conflict.other());
            double myTime = myEta + conflict.arc() / Math.max(v.v, 3.0);
            // Anyone still before the meeting point on that path, or just past it.
            for (Vehicle o : byConnector.getOrDefault(other.id(), List.of())) {
                double ahead = conflict.otherArc() - o.arc;
                if (ahead > -o.type.length() - 1.5 && (conflict.giveWay() || ahead < 12)) {
                    return true;
                }
            }
            if (!conflict.giveWay()) {
                continue;
            }
            // Traffic approaching that path with right of way, and allowed to go.
            RoadNetwork.Link approach = network.link(other.fromLink());
            if (network.signal(approach, time) == RoadNetwork.Signal.RED) {
                continue;
            }
            for (Vehicle o : byLane.getOrDefault(laneKey(approach.id(), other.fromLane()), List.of())) {
                if (o.plan != other || o.onConnector) {
                    continue;
                }
                double toStop = approach.length() - o.arc - o.half();
                double eta = (toStop + conflict.otherArc()) / Math.max(o.v, 1.0);
                if (o.v > 1.0 && eta < myTime + GAP_ACCEPTANCE && toStop < 70) {
                    return true;
                }
            }
        }
        // The driven car coming through a junction without signals.
        if (ego != null && !network.junction(mine.junction()).isSignalised() && ego.speed() > 1.5) {
            Point2 jp = network.junction(mine.junction()).position();
            double dx = jp.x() - ego.x();
            double dy = jp.y() - ego.y();
            double d = Math.hypot(dx, dy);
            boolean towards = (dx * Math.cos(ego.heading()) + dy * Math.sin(ego.heading())) > 0.3 * d;
            boolean hasToYield = mine.conflicts().stream().anyMatch(RoadNetwork.Conflict::giveWay);
            if (d < 30 && towards && hasToYield && distance(v, ego) > 6) {
                return true;
            }
        }
        return false;
    }

    /** The lane we would come out into is backed up to the junction. */
    private boolean exitBlocked(RoadNetwork.Connector c) {
        for (Vehicle o : byLane.getOrDefault(laneKey(c.toLink(), c.toLane()), List.of())) {
            return o.arc - o.half() < 4 && o.v < 1.0;
        }
        return false;
    }

    private boolean junctionClear(RoadNetwork.Connector mine) {
        for (RoadNetwork.Conflict conflict : mine.conflicts()) {
            for (Vehicle o : byConnector.getOrDefault(conflict.other(), List.of())) {
                if (o.v > 0.5) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Inside a junction: do not drive into a vehicle that is at our meeting point first. */
    private double conflictInside(Vehicle v) {
        RoadNetwork.Connector c = v.currentConnector();
        double best = LOOK_AHEAD;
        for (RoadNetwork.Conflict conflict : c.conflicts()) {
            double toMeet = conflict.arc() - v.arc - v.half();
            if (toMeet < -v.type.length() || toMeet > 30) {
                continue;
            }
            for (Vehicle o : byConnector.getOrDefault(conflict.other(), List.of())) {
                double otherToMeet = conflict.otherArc() - o.arc;
                boolean occupying = otherToMeet < o.half() + 1 && otherToMeet > -o.type.length() - 1;
                boolean earlier = otherToMeet > 0 && otherToMeet / Math.max(o.v, 0.5) < Math.max(0, toMeet) / Math.max(v.v, 0.5);
                if ((occupying || earlier && conflict.giveWay()) && toMeet > 0.2) {
                    best = Math.min(best, toMeet - 1.0);
                }
            }
        }
        return best;
    }

    /** Anything in the vehicle's way that is not traffic: the driven car, pedestrians, barriers. */
    private double[] inPath(Vehicle v, Ego ego, List<Obstacle> obstacles) {
        double best = LOOK_AHEAD;
        double speed = 0;
        OrientedBox me = v.obstacle.box();
        double c = Math.cos(me.heading());
        double s = Math.sin(me.heading());
        List<double[]> things = new ArrayList<>(); // {x, y, halfLength, halfWidth, vx, vy}
        if (ego != null) {
            things.add(new double[] {ego.x(), ego.y(), ego.halfLength(), ego.halfWidth(), ego.vx(), ego.vy()});
        }
        for (Obstacle o : obstacles) {
            things.add(new double[] {o.box().cx(), o.box().cy(), Math.max(o.box().halfLength(), o.box().halfWidth()),
                    Math.max(o.box().halfLength(), o.box().halfWidth()), o.vx(), o.vy()});
        }
        for (double[] t : things) {
            double dx = t[0] - me.cx();
            double dy = t[1] - me.cy();
            double ahead = dx * c + dy * s;
            double side = -dx * s + dy * c;
            if (ahead <= 0 || ahead > 60) {
                continue;
            }
            if (Math.abs(side) > me.halfWidth() + t[3] + 0.4) {
                continue;
            }
            double gap = ahead - me.halfLength() - t[2];
            if (gap < best) {
                best = gap;
                speed = Math.max(0, t[4] * c + t[5] * s);
            }
        }
        // Other vehicles whose body reaches into our path (a long vehicle's tail across a
        // junction, or someone turning in front): the lane bookkeeping above misses these.
        long cell = cellKey(me.cx(), me.cy());
        int cx = (int) (cell >> 32);
        int cy = (int) cell;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                for (Vehicle o : byCell.getOrDefault(((long) (cx + i) << 32) ^ ((cy + j) & 0xffffffffL), List.of())) {
                    if (o == v) {
                        continue;
                    }
                    double[] corners = o.obstacle.box().corners();
                    for (int k = 0; k < 8; k += 2) {
                        int m = (k + 2) % 8;
                        for (double f = 0; f <= 1.0; f += 0.5) {
                            double px = corners[k] + (corners[m] - corners[k]) * f;
                            double py = corners[k + 1] + (corners[m + 1] - corners[k + 1]) * f;
                            double dx = px - me.cx();
                            double dy = py - me.cy();
                            double ahead = dx * c + dy * s;
                            double side = -dx * s + dy * c;
                            if (ahead <= me.halfLength() * 0.5 || Math.abs(side) > me.halfWidth() + 0.25) {
                                continue;
                            }
                            double gap = ahead - me.halfLength();
                            if (gap < best) {
                                best = Math.max(0, gap);
                                speed = Math.max(0, o.v * Math.cos(o.obstacle.box().heading() - me.heading()));
                            }
                        }
                    }
                }
            }
        }
        return new double[] {best, speed};
    }

    private static long cellKey(double x, double y) {
        int cx = (int) Math.floor(x / 20);
        int cy = (int) Math.floor(y / 20);
        return ((long) cx << 32) ^ (cy & 0xffffffffL);
    }

    // ---- lanes ------------------------------------------------------------------------------------

    private void laneChoice(Vehicle v, double dt, RoadNetwork.Link link) {
        if (v.lc < 1) {
            v.lc = Math.min(1, v.lc + dt / (v.type.kind() == Obstacle.Kind.MOTORBIKE ? 2.0 : LANE_CHANGE_TIME));
            if (v.lc >= 1) {
                v.lane = v.targetLane;
            }
            return;
        }
        if (link.lanes() < 2) {
            return;
        }
        double toEnd = link.length() - v.arc;
        int required = v.plan == null ? -1 : v.plan.fromLane();
        if (required >= 0 && required != v.lane) {
            int step = required > v.lane ? 1 : -1;
            if (safeToChange(v, link, v.lane + step, 4.0)) {
                startChange(v, v.lane + step);
            } else if (toEnd < 20 && v.v < 1) {
                v.waited += dt;
                if (v.waited > 6) {
                    replanFromLane(v); // could not get over: take a turn from this lane instead
                    v.waited = 0;
                }
            }
            return;
        }
        v.mobilTimer -= dt;
        if (v.mobilTimer > 0 || toEnd < 50) {
            return;
        }
        v.mobilTimer = 1.0;
        double[] lead = leader(v, v.lane);
        double current = idm(v, lead[0], lead[1], desiredSpeed(v, link.speedLimit()));
        for (int candidate : new int[] {v.lane - 1, v.lane + 1}) {
            if (candidate < 0 || candidate >= link.lanes()) {
                continue;
            }
            if (v.type.isHeavy() && candidate > 1) {
                continue; // buses and trucks keep to the left lanes
            }
            if (required >= 0 && toEnd < 150 && Math.abs(candidate - required) > Math.abs(v.lane - required)) {
                continue;
            }
            double[] newLead = firstInLane(link.id(), candidate, v.arc, -v.arc - v.half());
            double gain = idm(v, newLead[0], newLead[1], desiredSpeed(v, link.speedLimit())) - current;
            double threshold = candidate > v.lane ? MOBIL_THRESHOLD + MOBIL_RIGHT_BIAS : MOBIL_THRESHOLD - MOBIL_RIGHT_BIAS;
            if (gain > threshold && safeToChange(v, link, candidate, SAFE_DECEL)) {
                startChange(v, candidate);
                return;
            }
        }
    }

    private boolean safeToChange(Vehicle v, RoadNetwork.Link link, int lane, double maxFollowerDecel) {
        double[] lead = firstInLane(link.id(), lane, v.arc - v.half(), -v.arc - v.half());
        if (lead[0] < 2 + v.v * 0.4) {
            return false;
        }
        Vehicle f = follower(link.id(), lane, v.arc + v.half(), v);
        if (f != null) {
            double gap = v.arc - v.half() - f.arc - f.half();
            if (gap < 2) {
                return false;
            }
            double decel = idm(f, gap, v.v, desiredSpeed(f, link.speedLimit()));
            if (decel < -maxFollowerDecel) {
                return false;
            }
        }
        return true;
    }

    private void startChange(Vehicle v, int lane) {
        v.targetLane = lane;
        v.lc = 0;
        v.mobilTimer = 3;
    }

    // ---- routes -----------------------------------------------------------------------------------

    /** Picks what to do at the end of the current link: mostly straight on, sometimes a turn. */
    private void choosePlan(Vehicle v) {
        v.plan = null;
        v.committed = false;
        v.waited = 0;
        RoadNetwork.Link link = v.currentLink();
        if (network.junction(link.to()).isBoundary()) {
            return;
        }
        Map<Integer, List<RoadNetwork.Connector>> byTarget = new HashMap<>();
        for (RoadNetwork.Connector c : network.connectorsFrom(link.id())) {
            RoadNetwork.Link to = network.link(c.toLink());
            if (!to.isRendered() || to.name().equals("Proving Ground Road")) {
                continue;
            }
            byTarget.computeIfAbsent(c.toLink(), k -> new ArrayList<>()).add(c);
        }
        List<Integer> targets = new ArrayList<>(byTarget.keySet());
        Collections.sort(targets);
        double total = 0;
        double[] weights = new double[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            RoadNetwork.Turn turn = byTarget.get(targets.get(i)).get(0).turn();
            weights[i] = switch (turn) {
                case STRAIGHT -> 3.0;
                case LEFT -> 1.0;
                case RIGHT -> 0.9;
                case UTURN -> targets.size() == 1 ? 1 : 0.02;
            };
            total += weights[i];
        }
        if (targets.isEmpty()) {
            return;
        }
        double u = random.nextDouble() * total;
        int pick = 0;
        for (int i = 0; i < weights.length; i++) {
            u -= weights[i];
            if (u <= 0) {
                pick = i;
                break;
            }
        }
        v.plan = nearestLane(byTarget.get(targets.get(pick)), v.lane);
    }

    private static RoadNetwork.Connector nearestLane(List<RoadNetwork.Connector> options, int lane) {
        RoadNetwork.Connector best = options.get(0);
        for (RoadNetwork.Connector c : options) {
            if (Math.abs(c.fromLane() - lane) < Math.abs(best.fromLane() - lane)) {
                best = c;
            }
        }
        return best;
    }

    private void replanFromLane(Vehicle v) {
        List<RoadNetwork.Connector> fromHere = new ArrayList<>();
        for (RoadNetwork.Connector c : network.connectorsFrom(v.link)) {
            if (c.fromLane() == v.lane && network.link(c.toLink()).isRendered()) {
                fromHere.add(c);
            }
        }
        if (!fromHere.isEmpty()) {
            v.plan = fromHere.get(random.nextInt(fromHere.size()));
        }
    }

    // ---- moving -----------------------------------------------------------------------------------

    private void advance(Vehicle v, double dt) {
        v.arc += v.v * dt;
        if (!v.onConnector) {
            RoadNetwork.Link link = v.currentLink();
            if (v.arc >= link.length()) {
                if (v.plan == null) {
                    v.remove = true; // left the map
                    return;
                }
                double carry = v.arc - link.length();
                if (v.lc < 1) {
                    v.lane = v.targetLane;
                    v.lc = 1;
                }
                v.onConnector = true;
                v.connector = v.plan.id();
                v.arc = carry;
                junctionEntries++;
            }
        }
        if (v.onConnector) {
            RoadNetwork.Connector c = v.currentConnector();
            if (v.arc >= c.length()) {
                double carry = v.arc - c.length();
                v.onConnector = false;
                v.connector = -1;
                v.link = c.toLink();
                v.lane = c.toLane();
                v.targetLane = v.lane;
                v.lc = 1;
                v.arc = carry;
                choosePlan(v);
            }
        }
        place(v, dt);
    }

    /** Puts the obstacle where the vehicle is, facing along its path. */
    private void place(Vehicle v, double dt) {
        Point2 p;
        double heading;
        if (v.onConnector) {
            Polyline path = v.currentConnector().path();
            p = path.pointAt(v.arc);
            heading = path.headingAt(v.arc);
        } else {
            RoadNetwork.Link link = v.currentLink();
            Polyline centre = link.centre();
            double arc = Math.min(v.arc, link.length());
            Point2 c = centre.pointAt(arc);
            double h = centre.headingAt(arc);
            double offset = v.offset();
            p = new Point2(c.x() - Math.sin(h) * offset, c.y() + Math.cos(h) * offset);
            heading = h;
            if (v.lc < 1) {
                double rate = (link.laneOffset(v.targetLane) - link.laneOffset(v.lane)) * 6 * v.lc * (1 - v.lc)
                        / (v.type.kind() == Obstacle.Kind.MOTORBIKE ? 2.0 : LANE_CHANGE_TIME);
                heading += Math.atan2(rate, Math.max(1, v.v));
            }
        }
        v.obstacle.moveTo(p.x(), p.y(), heading, dt);
        if (dt == 0) {
            v.obstacle.stop();
        }
    }

    /** Short state of the i-th vehicle (for tests and debugging). */
    String describe(int i) {
        Vehicle v = vehicles.get(i);
        return String.format("%s #%d %s %d lane %d->%d lc %.2f arc %.1f v %.1f plan %s", v.type.label(), v.id,
                v.onConnector ? "conn" : "link", v.onConnector ? v.connector : v.link, v.lane, v.targetLane, v.lc,
                v.arc, v.v, v.plan == null ? "-" : v.plan.id() + "/" + v.plan.turn());
    }

    /** Every vehicle's footprint (for tests). */
    public List<OrientedBox> boxes() {
        List<OrientedBox> result = new ArrayList<>();
        for (Vehicle v : vehicles) {
            result.add(v.obstacle.box());
        }
        return result;
    }
}
