package com.selfdriving.autopilot;

import com.selfdriving.navigation.Route;
import com.selfdriving.world.Point2;

/**
 * Pure pursuit path tracking: pick a point on the path a look-ahead distance in front of the
 * car and steer along the circle that passes through it:
 * {@code delta = atan(2 L sin(alpha) / Ld)}, where {@code alpha} is the angle from the car's
 * heading to that point (measured from the rear axle) and {@code Ld} the distance to it.
 *
 * <p>The look-ahead grows with speed: short in town for tight corners, long at speed for
 * smooth, stable lane keeping.
 */
public final class PurePursuit {

    private static final double MIN_LOOKAHEAD = 4.0;
    private static final double MAX_LOOKAHEAD = 25.0;
    private static final double LOOKAHEAD_PER_SPEED = 0.55;

    private final double wheelbase;
    private final double cgToRear;

    public PurePursuit(double wheelbase, double cgToRear) {
        this.wheelbase = wheelbase;
        this.cgToRear = cgToRear;
    }

    /** Look-ahead distance for a speed, m. */
    public static double lookahead(double speed) {
        return Math.max(MIN_LOOKAHEAD, Math.min(MAX_LOOKAHEAD, 2.5 + LOOKAHEAD_PER_SPEED * Math.abs(speed)));
    }

    /**
     * @param route    path to follow
     * @param carArc   the car's progress along the route, m
     * @param x        car centre east
     * @param y        car centre north
     * @param heading  car heading, rad
     * @param speed    car speed, m/s
     * @return road-wheel angle, rad (positive = left)
     */
    public double steer(Route route, double carArc, double x, double y, double heading, double speed) {
        double rearX = x - Math.cos(heading) * cgToRear;
        double rearY = y - Math.sin(heading) * cgToRear;
        Point2 target = route.pointAt(carArc + lookahead(speed));
        double dx = target.x() - rearX;
        double dy = target.y() - rearY;
        double distance = Math.max(1.0, Math.hypot(dx, dy));
        double alpha = Math.atan2(dy, dx) - heading;
        alpha = Math.atan2(Math.sin(alpha), Math.cos(alpha));
        return Math.atan(2 * wheelbase * Math.sin(alpha) / distance);
    }
}
