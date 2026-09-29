package com.selfdriving.autopilot;

import java.util.List;

import com.selfdriving.navigation.Route;
import com.selfdriving.navigation.RouteTracker;
import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.VehicleModel;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.world.Obstacle;

/**
 * Drives the car along a route.
 *
 * <p>Each tick it decides a target speed, the lowest of:
 * <ul>
 *   <li>the route's speed profile (speed limits, comfortable cornering, stopping at the end),</li>
 *   <li>the driver's maximum autopilot speed setting,</li>
 *   <li>a safe speed behind anything in the lane ahead: keep a gap of 4 m + 1.2 s, and never
 *       go faster than lets the car stop comfortably before it ({@code v^2 = v_lead^2 + 2 a d}).</li>
 * </ul>
 * then turns it into pedals ({@link SpeedController}) and steers with {@link PurePursuit}.
 *
 * <p>These are <em>requests</em>: the safety controller can still overrule them.
 */
public final class Autopilot {

    /** What the autopilot wants this tick. */
    public record Command(double throttle, double brake, double regen, double steerAngle, double targetSpeed,
                          int leadObjectId, String status) {
    }

    private static final double LANE_HALF_WIDTH = 1.9;
    private static final double FOLLOW_DECEL = 2.5;
    private static final double MIN_GAP = 4.0;
    private static final double TIME_GAP = 1.2;
    private static final double STEER_RATE = Math.toRadians(60);

    /**
     * Braking for something ahead starts once a constant deceleration of this much is needed to
     * stop at the wanted gap; from then on the car brakes at exactly the rate that ends there.
     */
    private static final double BRAKE_ONSET_DECEL = 1.2;

    private final VehicleParams params;
    private final SpeedController speedController;
    private final PurePursuit pursuit;
    private double steer;

    public Autopilot(VehicleParams params) {
        this.params = params;
        this.speedController = new SpeedController(params);
        this.pursuit = new PurePursuit(params.wheelbase(), params.cgToRearAxle());
    }

    public void reset(double currentSteer) {
        speedController.reset();
        steer = currentSteer;
    }

    /**
     * @param dt        time step, s
     * @param car       the car
     * @param tracker   progress along the route
     * @param objects   what the sensors see
     * @param maxSpeed  driver's maximum autopilot speed, m/s
     */
    public Command drive(double dt, VehicleModel car, RouteTracker tracker, List<SensorReadings.DetectedObject> objects,
                         double maxSpeed) {
        Route route = tracker.route();
        double v = car.forwardSpeed();
        double arc = tracker.arc();

        String status = "Following route";
        double target = Math.min(route.targetSpeedAt(arc), route.targetSpeedAt(arc + Math.max(0, v) * 0.6));
        if (target < route.speedLimitAt(arc) - 1 && tracker.remainingDistance() > 60) {
            status = target < v - 0.5 ? "Slowing for the bend" : "Cornering";
        }
        if (target > maxSpeed) {
            target = maxSpeed;
        }
        if (tracker.remainingDistance() < 60) {
            status = "Arriving at " + route.destination();
        }

        // Anything in our lane ahead limits the speed.
        int leadId = -1;
        double bestGap = Double.MAX_VALUE;
        double leadSpeed = 0;
        Obstacle.Kind leadKind = null;
        for (SensorReadings.DetectedObject o : objects) {
            double[] p = route.project(o.x(), o.y(), tracker.index());
            double size = Math.max(o.halfWidth(), Math.min(o.halfLength(), 1.2));
            if (p[0] <= arc || p[1] > LANE_HALF_WIDTH + size) {
                continue;
            }
            double gap = p[0] - arc - CarBody.FRONT - o.halfLength();
            if (gap < bestGap) {
                bestGap = gap;
                leadId = o.id();
                double heading = route.headingAt(p[0]);
                leadSpeed = Math.max(0, o.vx() * Math.cos(heading) + o.vy() * Math.sin(heading));
                leadKind = o.kind();
            }
        }
        double accelLimit = Double.POSITIVE_INFINITY;
        if (leadId >= 0) {
            double wantedGap = MIN_GAP + TIME_GAP * leadSpeed;
            double room = bestGap - wantedGap;
            double safe = room > 0 ? Math.sqrt(leadSpeed * leadSpeed + 2 * FOLLOW_DECEL * room) : Math.min(leadSpeed, 0.0);
            if (v > leadSpeed) {
                // Deceleration that ends exactly at the wanted gap: a = (v^2 - v_lead^2) / (2 d).
                accelLimit = room > 0.3
                        ? -(v * v - leadSpeed * leadSpeed) / (2 * room)
                        : -SpeedController.MAX_DECEL;
                if (accelLimit > -BRAKE_ONSET_DECEL) {
                    accelLimit = Double.POSITIVE_INFINITY; // still far enough away: keep driving normally
                }
            }
            if (safe < target) {
                target = safe;
                status = switch (leadKind) {
                    case PEDESTRIAN -> "Stopping for pedestrian";
                    case BARRIER -> "Stopping for obstruction";
                    default -> leadSpeed > 1 ? "Following vehicle" : "Stopping behind vehicle";
                };
            }
        }

        SpeedController.Pedals pedals = speedController.command(Math.max(0, target), accelLimit, car, dt);
        double wantedSteer = pursuit.steer(route, arc, car.x(), car.y(), car.heading(), v);
        wantedSteer = Math.max(-params.maxSteerAngle(), Math.min(params.maxSteerAngle(), wantedSteer));
        double maxChange = STEER_RATE * dt;
        steer += Math.max(-maxChange, Math.min(maxChange, wantedSteer - steer));

        return new Command(pedals.throttle(), pedals.brake(), pedals.regen(), steer, Math.max(0, target), leadId,
                status);
    }
}
