package com.selfdriving.simulation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.selfdriving.navigation.Route;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Point2;

/**
 * Repeatable test situations placed just ahead of the car, so they can be shown at any time:
 * <ul>
 *   <li><b>Pedestrian:</b> someone steps off the kerb and walks across the car's path.</li>
 *   <li><b>Stopped vehicle:</b> a car stands in the lane, waits, then pulls away along the road.</li>
 *   <li><b>Road closed:</b> a barrier closes the next road on the route (the simulation then re-routes).</li>
 * </ul>
 * Actors are ordinary {@link Obstacle}s with a {@link Obstacle.Behaviour}, so sensors, the
 * autopilot and collisions treat them exactly like anything else.
 */
public final class ScenarioManager {

    private static final double WALKING_SPEED = 1.4;
    private static final double KERB_OFFSET = 5.5;
    private static final double PULL_AWAY_SPEED = 9.0;
    /** Long enough for the test car to arrive and stop behind the vehicle from any speed. */
    private static final double VEHICLE_WAIT = 15.0;

    private final List<Obstacle> actors = new ArrayList<>();
    private int nextId = 1;

    /** Time a pedestrian needs to walk from the kerb to the middle of the lane, s. */
    public static double crossingTime() {
        return KERB_OFFSET / WALKING_SPEED;
    }

    /** Moves every actor; removes those that have finished. */
    public void update(double time, double dt) {
        Iterator<Obstacle> it = actors.iterator();
        while (it.hasNext()) {
            Obstacle o = it.next();
            if (o.behaviour() != null && !o.behaviour().update(o, time, dt)) {
                it.remove();
            }
        }
    }

    public List<Obstacle> actors() {
        return actors;
    }

    public void clear() {
        actors.clear();
    }

    /**
     * A pedestrian starts from the right-hand kerb at a point the car reaches in about three
     * seconds and walks across.
     *
     * @param ahead where the pedestrian's crossing point is (on the car's path)
     * @param pathHeading direction of the path there, rad
     */
    public Obstacle pedestrianCrossing(Point2 ahead, double pathHeading) {
        double rightX = Math.sin(pathHeading);
        double rightY = -Math.cos(pathHeading);
        double startX = ahead.x() + rightX * KERB_OFFSET;
        double startY = ahead.y() + rightY * KERB_OFFSET;
        double walkX = -rightX;
        double walkY = -rightY;
        double walkHeading = Math.atan2(walkY, walkX);
        Obstacle person = new Obstacle(nextId++, Obstacle.Kind.PEDESTRIAN,
                new OrientedBox(startX, startY, walkHeading, 0.25, 0.3), 1.75, "Pedestrian");
        double[] walked = {0};
        person.setBehaviour((o, time, dt) -> {
            walked[0] += WALKING_SPEED * dt;
            o.moveTo(startX + walkX * walked[0], startY + walkY * walked[0], walkHeading, dt);
            return walked[0] < 2 * KERB_OFFSET + 4;
        });
        actors.add(person);
        return person;
    }

    /**
     * A car stopped in the lane ahead. Once the test car has waited behind it, it pulls away and
     * drives along the route.
     *
     * @param route    the route being driven
     * @param arc      where on the route the car stands, m
     */
    public Obstacle stoppedVehicle(Route route, double arc, double startTime) {
        Point2 p = route.pointAt(arc);
        double heading = route.headingAt(arc);
        Obstacle car = new Obstacle(nextId++, Obstacle.Kind.CAR, new OrientedBox(p.x(), p.y(), heading, 2.3, 0.92),
                1.45, "Vehicle");
        double[] position = {arc};
        double[] speed = {0};
        car.setBehaviour((o, time, dt) -> {
            if (time - startTime < VEHICLE_WAIT) {
                o.stop();
                return true;
            }
            speed[0] = Math.min(PULL_AWAY_SPEED, speed[0] + 1.5 * dt);
            position[0] += speed[0] * dt;
            if (position[0] >= route.length() - 5) {
                return false;
            }
            Point2 q = route.pointAt(position[0]);
            o.moveTo(q.x(), q.y(), route.headingAt(position[0]), dt);
            return true;
        });
        actors.add(car);
        return car;
    }

    /**
     * A slow vehicle (an auto-rickshaw at walking-plus pace) in the lane ahead, driving along the
     * route: something to overtake.
     */
    public Obstacle slowVehicle(Route route, double arc, double speed) {
        Point2 p = route.pointAt(arc);
        Obstacle vehicle = new Obstacle(nextId++, Obstacle.Kind.AUTO_RICKSHAW,
                new OrientedBox(p.x(), p.y(), route.headingAt(arc), 1.45, 0.7), 1.75, "Auto-rickshaw");
        double[] position = {arc};
        vehicle.setBehaviour((o, time, dt) -> {
            position[0] += speed * dt;
            if (position[0] >= route.length() - 5) {
                return false;
            }
            Point2 q = route.pointAt(position[0]);
            o.moveTo(q.x(), q.y(), route.headingAt(position[0]), dt);
            return true;
        });
        actors.add(vehicle);
        return vehicle;
    }

    /** Barrier across a road, placed a little way into it. */
    public Obstacle roadBarrier(Point2 position, double roadHeading, double roadWidth) {
        Obstacle barrier = new Obstacle(nextId++, Obstacle.Kind.BARRIER,
                new OrientedBox(position.x(), position.y(), roadHeading, 0.4, roadWidth / 2), 1.1, "Road closed");
        actors.add(barrier);
        return barrier;
    }
}
