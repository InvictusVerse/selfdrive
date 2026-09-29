package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything static in the simulated world: the proving ground, the city, their roads and paint,
 * buildings and named places. Built once at start-up and shared read-only by all threads.
 */
public final class World {

    private final ProvingGround provingGround;
    private final City city;
    private final List<Road> roads;
    private final List<Marking> markings;
    private final List<Place> places;

    public World() {
        provingGround = new ProvingGround();
        city = new City(provingGround);

        List<Road> allRoads = new ArrayList<>(provingGround.roads());
        allRoads.addAll(city.roads());
        roads = List.copyOf(allRoads);

        List<Marking> allMarkings = new ArrayList<>(provingGround.markings());
        allMarkings.addAll(city.markings());
        markings = List.copyOf(allMarkings);

        List<Place> allPlaces = new ArrayList<>(city.places());
        allPlaces.add(new Place("Proving Ground", provingGround.mainJunction()));
        places = List.copyOf(allPlaces);
    }

    public ProvingGround provingGround() {
        return provingGround;
    }

    public City city() {
        return city;
    }

    public List<Road> roads() {
        return roads;
    }

    public List<Marking> markings() {
        return markings;
    }

    /** Buildings (static obstacles). */
    public List<Obstacle> buildings() {
        return city.buildings();
    }

    public List<Place> places() {
        return places;
    }

    public Pose start() {
        return provingGround.start();
    }

    /** Bounding box of all roads: {minX, minY, maxX, maxY}. */
    public double[] bounds() {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Road road : roads) {
            for (Point2 p : road.centre().points()) {
                minX = Math.min(minX, p.x() - road.width());
                minY = Math.min(minY, p.y() - road.width());
                maxX = Math.max(maxX, p.x() + road.width());
                maxY = Math.max(maxY, p.y() + road.width());
            }
        }
        return new double[] {minX, minY, maxX, maxY};
    }
}
