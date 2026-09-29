package com.selfdriving.autopilot;

import java.util.List;

import com.selfdriving.navigation.Route;
import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Point2;

/**
 * Forward collision warning and automatic emergency braking. Always on, in manual driving
 * and on autopilot, and it overrules both.
 *
 * <p>Every tick it predicts the next {@link #HORIZON} seconds: the car moves along its route
 * (autopilot) or along its current arc (manual, constant speed and yaw rate), and every detected
 * object moves with its measured velocity. The first moment the car's footprint (plus a margin)
 * would overlap an object is the <b>time to collision</b> (TTC).
 * <ul>
 *   <li><b>Warning</b> when TTC &lt; 2.5 s.</li>
 *   <li><b>Emergency braking</b> when the distance to the collision point is less than the
 *       stopping distance {@code v^2 / (2 * 0.85 * mu * g)} plus 0.25 s reaction and a 1.5 m margin,
 *       or when TTC &lt; 0.6 s. Braking then holds until the car has stopped and the path is clear.</li>
 * </ul>
 */
public final class SafetyController {

    /** Result for one tick. */
    public record Assessment(double timeToCollision, int threatId, boolean warning, boolean emergencyBraking) {

        public static Assessment clear() {
            return new Assessment(Double.POSITIVE_INFINITY, -1, false, false);
        }
    }

    /**
     * Where a detected object will be after some time. The default is a straight line at its
     * measured velocity; the simulation supplies a better guess for vehicles (they follow their lane).
     */
    @FunctionalInterface
    public interface Predictor {
        OrientedBox at(SensorReadings.DetectedObject object, double seconds);
    }

    /** Straight line at the measured velocity. */
    public static final Predictor STRAIGHT = (o, t) -> new OrientedBox(o.x() + o.vx() * t, o.y() + o.vy() * t,
            o.heading(), o.halfLength(), o.halfWidth());

    public static final double HORIZON = 3.0;
    public static final double WARNING_TTC = 2.5;
    private static final double LAST_MOMENT_TTC = 0.6;
    private static final double STEP = 0.05;
    private static final double MARGIN = 0.35;
    private static final double REACTION = 0.25;
    private static final double BUFFER = 1.5;
    private static final double USABLE_FRICTION = 0.85;
    private static final double HOLD_CLEARANCE = 8.0;

    private boolean braking;
    private int brakingFor = -1;

    /**
     * @param enabled  whether emergency braking is switched on
     * @param x        car centre east
     * @param y        car centre north
     * @param heading  car heading, rad
     * @param speed    forward speed, m/s
     * @param yawRate  rad/s
     * @param route    route being followed on autopilot, or null
     * @param routeArc car's progress along the route, m
     * @param objects  detected objects
     * @param friction road friction coefficient
     */
    public Assessment assess(boolean enabled, double x, double y, double heading, double speed, double yawRate,
                             Route route, double routeArc, List<SensorReadings.DetectedObject> objects,
                             double friction) {
        return assess(enabled, x, y, heading, speed, yawRate, route, routeArc, objects, friction, STRAIGHT);
    }

    /** As above, with a way to predict how objects move. */
    public Assessment assess(boolean enabled, double x, double y, double heading, double speed, double yawRate,
                             Route route, double routeArc, List<SensorReadings.DetectedObject> objects,
                             double friction, Predictor predictor) {
        if (!enabled) {
            braking = false;
            return Assessment.clear();
        }
        double ttc = Double.POSITIVE_INFINITY;
        int threat = -1;
        double offset = CarBody.centreOffset();
        double halfLength = CarBody.halfLength() + MARGIN;
        double halfWidth = CarBody.HALF_WIDTH + MARGIN;

        for (double t = 0; t <= HORIZON && Double.isInfinite(ttc); t += STEP) {
            double px;
            double py;
            double ph;
            if (route != null) {
                double arc = routeArc + Math.max(0, speed) * t;
                Point2 p = route.pointAt(arc);
                px = p.x();
                py = p.y();
                ph = route.headingAt(arc);
            } else if (Math.abs(yawRate) < 1e-3) {
                px = x + Math.cos(heading) * speed * t;
                py = y + Math.sin(heading) * speed * t;
                ph = heading;
            } else {
                double r = speed / yawRate;
                ph = heading + yawRate * t;
                px = x + r * (Math.sin(ph) - Math.sin(heading));
                py = y - r * (Math.cos(ph) - Math.cos(heading));
            }
            OrientedBox car = new OrientedBox(px + Math.cos(ph) * offset, py + Math.sin(ph) * offset, ph,
                    halfLength, halfWidth);
            for (SensorReadings.DetectedObject o : objects) {
                if (isFollowing(o, x, y, heading)) {
                    continue; // braking cannot avoid a vehicle catching up from behind
                }
                OrientedBox future = predictor.at(o, t);
                if (car.overlap(future) != null) {
                    ttc = t;
                    threat = o.id();
                    break;
                }
            }
        }

        double v = Math.max(0, speed);
        boolean warning = ttc < WARNING_TTC && v > 1.0;
        if (!braking && v > 0.5 && !Double.isInfinite(ttc)) {
            double distanceToImpact = v * ttc;
            double stopping = v * v / (2 * USABLE_FRICTION * friction * VehicleParams.GRAVITY) + v * REACTION + BUFFER;
            if (distanceToImpact < stopping || ttc < LAST_MOMENT_TTC) {
                braking = true;
                brakingFor = threat;
            }
        }
        if (braking && v < 0.2 && !pathBlocked(x, y, heading, objects)) {
            braking = false;
            brakingFor = -1;
        }
        return new Assessment(ttc, braking ? brakingFor : threat, warning, braking);
    }

    /** A vehicle behind the car going the same way. */
    private static boolean isFollowing(SensorReadings.DetectedObject o, double x, double y, double heading) {
        double ahead = (o.x() - x) * Math.cos(heading) + (o.y() - y) * Math.sin(heading);
        double dh = Math.atan2(Math.sin(o.heading() - heading), Math.cos(o.heading() - heading));
        return ahead < 0 && Math.abs(dh) < Math.toRadians(60);
    }

    /** Whether something is still right in front of a stopped car. */
    private static boolean pathBlocked(double x, double y, double heading, List<SensorReadings.DetectedObject> objects) {
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        for (SensorReadings.DetectedObject o : objects) {
            double dx = o.x() - x;
            double dy = o.y() - y;
            double ahead = dx * c + dy * s;
            double side = -dx * s + dy * c;
            if (ahead > 0 && ahead < CarBody.FRONT + HOLD_CLEARANCE
                    && Math.abs(side) < CarBody.HALF_WIDTH + o.halfWidth() + 0.5) {
                return true;
            }
        }
        return false;
    }

    public boolean isBraking() {
        return braking;
    }

    public void reset() {
        braking = false;
        brakingFor = -1;
    }
}
