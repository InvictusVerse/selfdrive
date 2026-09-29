package com.selfdriving.autopilot;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleModel;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.sensors.SensorReadings;

/**
 * Drives a parking plan: first straight along the lane to the start pose, then each segment of
 * the manoeuvre, stopping and changing between Drive and Reverse where the direction changes.
 *
 * <p>Steering uses each arc's own steering angle (feed-forward) plus a correction that turns the
 * car's heading towards the path. Going backwards the correction flips sign, because
 * {@code yaw rate = v tan(delta) / L} with negative v.
 *
 * <p>The parking sensors guard every move: a segment ends early if something comes within
 * 0.35 m in the direction of travel, and the car pauses if anything moves close by.
 */
public final class ParkingController {

    /** What to do this tick. */
    public record Command(double throttle, double brake, double steerAngle, Gear gear, boolean finished,
                          boolean failed, String status) {
    }

    private static final double SPEED = 1.1;
    private static final double APPROACH_SPEED = 2.8;
    private static final double HEADING_GAIN = 1.4;
    private static final double SIDE_GAIN = 0.9;
    private static final double STOP_MARGIN = 0.35;
    private static final double STEER_RATE = Math.toRadians(90);
    private static final double END_TOLERANCE = 0.08;

    private final VehicleParams params;
    private final ParkingPlanner planner;
    private final ParkingPlanner.Plan plan;
    private final List<List<ParkingPlanner.Pose>> paths = new ArrayList<>();
    private final List<Integer> directions = new ArrayList<>();
    private int segment;
    private boolean switching = true;
    private double steer;
    private double pause;

    public ParkingController(VehicleParams params, ParkingPlanner planner, ParkingPlanner.Plan plan,
                             VehicleModel car) {
        this.params = params;
        this.planner = planner;
        this.plan = plan;
        this.steer = 0;
        // Approach: straight along the lane (forward or back) to the start pose.
        ParkingPlanner.Pose here = planner.rearAxle(car.x(), car.y(), car.heading());
        double along = (plan.start().x() - here.x()) * Math.cos(here.heading())
                + (plan.start().y() - here.y()) * Math.sin(here.heading());
        List<ParkingPlanner.Pose> approach = new ArrayList<>();
        int steps = Math.max(2, (int) Math.ceil(Math.abs(along) / 0.25));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            // Blend from the car's pose to the start pose (they are in the same lane).
            approach.add(new ParkingPlanner.Pose(here.x() + (plan.start().x() - here.x()) * t,
                    here.y() + (plan.start().y() - here.y()) * t, plan.start().heading()));
        }
        if (Math.abs(along) > 0.3) {
            paths.add(approach);
            directions.add(along >= 0 ? 1 : -1);
        }
        ParkingPlanner.Pose p = plan.start();
        for (ParkingPlanner.Segment s : plan.segments()) {
            if (Math.abs(s.length()) < 0.05) {
                continue;
            }
            paths.add(ParkingPlanner.sample(p, s));
            directions.add(s.direction());
            p = ParkingPlanner.advance(p, s.curvature(), s.length());
        }
    }

    public ParkingPlanner.Plan plan() {
        return plan;
    }

    /** Progress through the manoeuvre, 0..1. */
    public double progress() {
        return paths.isEmpty() ? 1 : Math.min(1, (double) segment / paths.size());
    }

    public Command update(double dt, VehicleModel car, Gear gear, SensorReadings readings) {
        if (segment >= paths.size()) {
            return stop(Gear.PARK, true, "Parked");
        }
        int direction = directions.get(segment);
        Gear wanted = direction > 0 ? Gear.DRIVE : Gear.REVERSE;
        double v = car.forwardSpeed();
        if (switching || gear != wanted) {
            // Stop, then change gear, before every change of direction.
            if (Math.abs(v) > 0.05) {
                return stop(gear, false, status(direction));
            }
            switching = false;
            return new Command(0, 1, steer, wanted, false, false, status(direction));
        }

        List<ParkingPlanner.Pose> path = paths.get(segment);
        ParkingPlanner.Pose rear = planner.rearAxle(car.x(), car.y(), car.heading());
        int nearest = nearest(path, rear);
        double remaining = remaining(path, nearest, rear);
        boolean blocked = blocked(readings, direction);
        if (remaining < END_TOLERANCE || blocked || nearest == path.size() - 1 && passedEnd(path, rear, direction)) {
            segment++;
            switching = true;
            return stop(gear, segment >= paths.size(), status(direction));
        }
        if (somethingMovingClose(readings, car)) {
            pause = 1.0;
        }
        if (pause > 0) {
            pause -= dt;
            return stop(gear, false, "Waiting: someone is close to the car");
        }

        // Steering: the arc's own steering (feed-forward) plus a correction that steers the heading
        // towards the path. Wanted heading = path heading turned against the sideways error;
        // going backwards the correction works the other way round (yaw rate = v tan(delta) / L).
        double pathHeading = path.get(nearest).heading();
        double ex = rear.x() - path.get(nearest).x();
        double ey = rear.y() - path.get(nearest).y();
        double sideError = -ex * Math.sin(pathHeading) + ey * Math.cos(pathHeading);
        double headingError = Math.atan2(Math.sin(car.heading() - pathHeading), Math.cos(car.heading() - pathHeading));
        double feedForward = Math.atan(params.wheelbase() * curvatureOf(segment));
        double wantedSteer = direction > 0
                ? feedForward - HEADING_GAIN * (headingError + Math.atan(SIDE_GAIN * sideError))
                : feedForward + HEADING_GAIN * (headingError - Math.atan(SIDE_GAIN * sideError));
        wantedSteer = Math.max(-params.maxSteerAngle(), Math.min(params.maxSteerAngle(), wantedSteer));
        double maxChange = STEER_RATE * dt;
        steer += Math.max(-maxChange, Math.min(maxChange, wantedSteer - steer));

        // Speed: creep, slowing for the end of the segment (and waiting while the wheels turn).
        double steerLag = Math.abs(wantedSteer - steer);
        boolean approaching = segment == 0 && paths.size() > plan.segments().stream().filter(s -> Math.abs(s.length()) >= 0.05).count();
        double targetSpeed = Math.min(approaching ? APPROACH_SPEED : SPEED, Math.sqrt(2 * 0.6 * Math.max(0, remaining)) + 0.15);
        if (steerLag > Math.toRadians(8)) {
            targetSpeed = 0.25;
        }
        double speed = Math.abs(v);
        double error = targetSpeed - speed;
        double throttle = error > 0 ? Math.min(0.4, 0.06 + 0.25 * error) : 0;
        double brake = error < -0.15 ? Math.min(0.6, -0.5 * error) : 0;
        return new Command(throttle, brake, steer, wanted, false, false, status(direction));
    }

    /** Steering curvature of a segment (0 for the approach). */
    private double curvatureOf(int index) {
        int offset = paths.size() - plan.segments().stream().filter(s -> Math.abs(s.length()) >= 0.05).toList().size();
        int k = index - offset;
        if (k < 0) {
            return 0;
        }
        return plan.segments().stream().filter(s -> Math.abs(s.length()) >= 0.05).toList().get(k).curvature();
    }

    private Command stop(Gear gear, boolean finished, String status) {
        return new Command(0, 1, steer, gear, finished, false, status);
    }

    private String status(int direction) {
        if (segment == 0 && paths.size() > plan.segments().size()) {
            return direction > 0 ? "Parking: moving up to the space" : "Parking: backing up to the space";
        }
        return direction > 0 ? "Parking: straightening up" : plan.kind().equals("bay")
                ? "Parking: reversing into the bay" : "Parking: reversing into the space";
    }

    private static int nearest(List<ParkingPlanner.Pose> path, ParkingPlanner.Pose p) {
        int best = 0;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            double d = Math.hypot(path.get(i).x() - p.x(), path.get(i).y() - p.y());
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    private static double remaining(List<ParkingPlanner.Pose> path, int from, ParkingPlanner.Pose p) {
        ParkingPlanner.Pose end = path.get(path.size() - 1);
        double along = 0;
        for (int i = from; i + 1 < path.size(); i++) {
            along += Math.hypot(path.get(i + 1).x() - path.get(i).x(), path.get(i + 1).y() - path.get(i).y());
        }
        return Math.min(along + 0.1, Math.hypot(end.x() - p.x(), end.y() - p.y()) + (from < path.size() - 3 ? 1 : 0));
    }

    /** The car has gone past the end of the segment in its direction of travel. */
    private static boolean passedEnd(List<ParkingPlanner.Pose> path, ParkingPlanner.Pose p, int direction) {
        ParkingPlanner.Pose end = path.get(path.size() - 1);
        double h = end.heading();
        double along = (p.x() - end.x()) * Math.cos(h) + (p.y() - end.y()) * Math.sin(h);
        return along * direction > -END_TOLERANCE;
    }

    /** A parking sensor in the direction of travel sees something closer than the stop margin. */
    private static boolean blocked(SensorReadings r, int direction) {
        float[] u = r.ultrasonic();
        if (u.length < 8) {
            return false;
        }
        // The centre sensors look along the direction of travel; the corner ones look sideways at
        // neighbouring cars, which are meant to be close while squeezing into a space.
        int from = direction > 0 ? 1 : 5;
        for (int i = from; i < from + 2; i++) {
            if (!Float.isNaN(u[i]) && u[i] < STOP_MARGIN) {
                return true;
            }
        }
        return false;
    }

    private static boolean somethingMovingClose(SensorReadings r, VehicleModel car) {
        for (SensorReadings.DetectedObject o : r.detected()) {
            if (Math.hypot(o.vx(), o.vy()) > 0.4 && o.distance() < 6) {
                return true;
            }
        }
        return false;
    }
}
