package com.selfdriving.navigation;

/**
 * Follows the car's progress along a route: distance driven and remaining, time left, the next
 * direction, and whether the car has left the route.
 *
 * <p>Owned by the simulation thread.
 */
public final class RouteTracker {

    /** Further than this from the path, the car counts as off the route, m. */
    public static final double OFF_ROUTE_DISTANCE = 12;

    /** Within this distance of the end (and stopped), the car has arrived, m. */
    public static final double ARRIVAL_DISTANCE = 4;

    private final Route route;
    private int index;
    private double arc;
    private double lateral;

    public RouteTracker(Route route) {
        this.route = route;
    }

    public Route route() {
        return route;
    }

    /** Updates progress for the car's position. */
    public void update(double x, double y) {
        double[] p = route.project(x, y, index);
        // Progress may slip back a little (e.g. reversing) but cannot jump backwards far.
        if (p[0] >= arc - 5) {
            arc = p[0];
            index = (int) p[2];
        }
        lateral = p[1];
    }

    /** Distance driven along the route, m. */
    public double arc() {
        return arc;
    }

    /** Index of the route point just behind the car (a search hint for projections). */
    public int index() {
        return index;
    }

    /** Distance from the car to the path, m. */
    public double lateralError() {
        return lateral;
    }

    public double remainingDistance() {
        return Math.max(0, route.length() - arc);
    }

    public double remainingSeconds() {
        return route.remainingSeconds(arc);
    }

    public boolean isOffRoute() {
        return lateral > OFF_ROUTE_DISTANCE;
    }

    public boolean isAtDestination() {
        return remainingDistance() < ARRIVAL_DISTANCE;
    }

    /** The next direction ahead of the car. */
    public Route.Maneuver nextManeuver() {
        for (Route.Maneuver m : route.maneuvers()) {
            if (m.arc() > arc - 2) {
                return m;
            }
        }
        return route.maneuvers().get(route.maneuvers().size() - 1);
    }

    /** Distance to the next direction, m. */
    public double distanceToNextManeuver() {
        return Math.max(0, nextManeuver().arc() - arc);
    }
}
