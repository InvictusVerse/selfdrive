package com.selfdriving.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.world.Place;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Pose;
import com.selfdriving.world.RoadNetwork;
import com.selfdriving.world.World;

class NavigationTest {

    private static final World WORLD = new World();
    private static final RoadNetwork NET = WORLD.network();
    private static final RoadGraph GRAPH = RoadGraph.build(WORLD);

    private static RoadGraph.Location startLocation() {
        Pose start = WORLD.start();
        return GRAPH.locate(start.x(), start.y(), start.heading()).orElseThrow();
    }

    private static PathFinder.Result route(PathFinder finder, RoadGraph.Location from, Place place, Set<Integer> closed) {
        RoadNetwork.Position goal = WORLD.nearestKerb(place.location());
        return finder.find(GRAPH, from.edge(), from.arc(), GRAPH.edge(goal.link().id()), goal.arc(), closed)
                .orElseThrow(() -> new AssertionError("no route to " + place.name()));
    }

    private static Route build(RoadGraph.Location from, Place place, PathFinder.Result result) {
        RoadNetwork.Position goal = WORLD.nearestKerb(place.location());
        return Route.build(NET, result.edges(), from.arc(), from.lane(), goal.arc(), place.name());
    }

    private static List<Place> cityPlaces() {
        return WORLD.places().stream().filter(p -> !p.name().equals("Proving Ground")).toList();
    }

    @Test
    @DisplayName("The real map becomes a lane-level network with junctions, turns and signals")
    void networkStructure() {
        long signalised = NET.junctions().stream().filter(RoadNetwork.Junction::isSignalised).count();
        long boundary = NET.junctions().stream().filter(RoadNetwork.Junction::isBoundary).count();
        System.out.printf("Network: %d junctions (%d signalised, %d at the map edge), %d links, %d turn paths, "
                        + "%d buildings, %d places%n", NET.junctions().size(), signalised, boundary, NET.links().size(),
                NET.connectors().size(), WORLD.buildings().size(), WORLD.places().size());
        assertTrue(NET.junctions().size() > 100);
        assertTrue(signalised >= 10, "signalised junctions: " + signalised);
        assertTrue(boundary >= 4, "roads leave the map");
        assertTrue(WORLD.places().size() >= 5);
        for (RoadNetwork.Link link : NET.links()) {
            assertTrue(link.length() > 0.05, "link " + link.id() + " " + link.name());
            if (!NET.junction(link.to()).isBoundary()) {
                assertFalse(NET.connectorsFrom(link.id()).isEmpty(), "no way on from " + link.name() + " " + link.id());
            }
        }
    }

    @Test
    @DisplayName("Traffic keeps left: each direction's lanes lie left of the centre line")
    void keepsLeft() {
        int checked = 0;
        for (RoadNetwork.Link link : NET.links()) {
            if (link.reverseId() < 0 || link.length() < 10) {
                continue;
            }
            RoadNetwork.Link back = NET.link(link.reverseId());
            assertTrue(link.laneOffset(0) > 0 && link.laneOffset(link.lanes() - 1) > 0);
            // Lane centres of the two directions are on opposite sides of the centre line.
            Point2 mid = link.centre().pointAt(link.length() / 2);
            double h = link.centre().headingAt(link.length() / 2);
            Point2 mine = link.lane(0).pointAt(link.length() / 2);
            Point2 theirs = back.lane(0).pointAt(back.length() / 2);
            double sideMine = -(mine.x() - mid.x()) * Math.sin(h) + (mine.y() - mid.y()) * Math.cos(h);
            double sideTheirs = -(theirs.x() - mid.x()) * Math.sin(h) + (theirs.y() - mid.y()) * Math.cos(h);
            assertTrue(sideMine > 0 && sideTheirs < 0, link.name());
            checked++;
        }
        assertTrue(checked > 50);
    }

    @Test
    @DisplayName("Left turns start from the left lane, right turns (across traffic) from the right lane")
    void turnLanes() {
        for (RoadNetwork.Connector c : NET.connectors()) {
            RoadNetwork.Link in = NET.link(c.fromLink());
            long ways = NET.connectorsFrom(in.id()).stream().mapToInt(RoadNetwork.Connector::toLink).distinct().count();
            if (ways < 2) {
                continue;
            }
            if (c.turn() == RoadNetwork.Turn.LEFT) {
                assertEquals(0, c.fromLane());
            } else if (c.turn() == RoadNetwork.Turn.RIGHT) {
                assertEquals(in.lanes() - 1, c.fromLane());
            }
        }
    }

    @Test
    @DisplayName("Signal phases never show green to crossing traffic at the same time")
    void signalPhases() {
        for (RoadNetwork.Junction j : NET.junctions()) {
            if (!j.isSignalised()) {
                continue;
            }
            for (double t = 0; t < j.signal().cycle(); t += 0.5) {
                Set<Integer> green = new HashSet<>();
                for (int id : j.incoming()) {
                    if (NET.signal(NET.link(id), t) == RoadNetwork.Signal.GREEN) {
                        green.add(NET.link(id).phase());
                    }
                }
                assertTrue(green.size() <= 1, "junction " + j.name() + " at " + t);
            }
        }
    }

    @Test
    @DisplayName("The start position is found in the left lane of a city road")
    void locatesStart() {
        RoadGraph.Location location = startLocation();
        assertEquals(0, location.lane());
        assertTrue(location.distance() < 0.5, "car starts in the lane centre");
    }

    @Test
    @DisplayName("A* and Dijkstra find equally fast routes to every place; A* examines fewer roads")
    void aStarMatchesDijkstra() {
        RoadGraph.Location start = startLocation();
        for (Place place : WORLD.places()) {
            PathFinder.Result a = route(PathFinder.aStar(), start, place, Set.of());
            PathFinder.Result d = route(PathFinder.dijkstra(), start, place, Set.of());
            assertEquals(d.seconds(), a.seconds(), 1e-6, place.name());
            assertTrue(a.expanded() <= d.expanded(), place.name() + ": A* " + a.expanded() + " vs " + d.expanded());
        }
    }

    @Test
    @DisplayName("Routes never make a U-turn at a junction")
    void noUTurns() {
        RoadGraph.Location start = startLocation();
        for (Place place : cityPlaces()) {
            List<RoadGraph.Edge> edges = route(PathFinder.aStar(), start, place, Set.of()).edges();
            for (int i = 1; i < edges.size(); i++) {
                assertNotEquals(edges.get(i - 1).reverseId(), edges.get(i).id(), place.name());
            }
        }
    }

    @Test
    @DisplayName("Closing a road on the route gives a different, slower route")
    void closedRoadForcesDetour() {
        RoadGraph.Location start = startLocation();
        for (Place place : cityPlaces()) {
            PathFinder.Result open = route(PathFinder.aStar(), start, place, Set.of());
            if (open.edges().size() < 4) {
                continue;
            }
            RoadGraph.Edge middle = open.edges().get(open.edges().size() / 2);
            Set<Integer> closed = new HashSet<>(Set.of(middle.id()));
            if (middle.reverseId() >= 0) {
                closed.add(middle.reverseId());
            }
            RoadNetwork.Position goal = WORLD.nearestKerb(place.location());
            var detour = PathFinder.aStar().find(GRAPH, start.edge(), start.arc(), GRAPH.edge(goal.link().id()),
                    goal.arc(), closed);
            if (detour.isEmpty()) {
                continue; // no way round this one; try another place
            }
            assertTrue(detour.get().edges().stream().noneMatch(e -> closed.contains(e.id())));
            assertTrue(detour.get().seconds() >= open.seconds());
            return;
        }
        throw new AssertionError("no detour found for any place");
    }

    @Test
    @DisplayName("A route is a smooth, continuous path in the right lanes, with real cornering speeds")
    void routeGeometryAndSpeeds() {
        RoadGraph.Location start = startLocation();
        int turns = 0;
        for (Place place : cityPlaces()) {
            Route route = build(start, place, route(PathFinder.aStar(), start, place, Set.of()));
            assertEquals(WORLD.start().x(), route.x(0), 0.5);
            assertEquals(WORLD.start().y(), route.y(0), 0.5);
            for (int i = 1; i < route.size(); i++) {
                double gap = Math.hypot(route.x(i) - route.x(i - 1), route.y(i) - route.y(i - 1));
                assertTrue(gap < Route.SPACING + 0.3, "gap " + gap + " at " + i + " to " + place.name());
            }
            for (int i = 0; i < route.size(); i++) {
                double arc = route.arc(i);
                assertTrue(route.targetSpeedAt(arc) <= route.speedLimitAt(arc) + 0.2);
            }
            assertEquals(0, route.targetSpeedAt(route.length()), 1e-9);
            assertEquals("Arrive at " + place.name(), route.maneuvers().get(route.maneuvers().size() - 1).instruction());
            // At each junction the car is already in the lane its turn starts from.
            for (Route.JunctionEntry j : route.junctions()) {
                RoadNetwork.Connector c = NET.connector(j.connector());
                Route.Stretch before = route.stretchAt(j.entryArc() - 0.5);
                assertEquals(c.fromLane(), before.toLane(), "lane before junction " + j.junction());
            }
            // Turns are taken at real-world speeds (about 12-30 km/h), not at the speed limit.
            for (Route.Maneuver m : route.maneuvers()) {
                boolean nearEnd = m.arc() > route.length() - 45;
                if (!nearEnd && (m.type() == Route.Maneuver.Type.LEFT || m.type() == Route.Maneuver.Type.RIGHT)) {
                    double slowest = Double.MAX_VALUE;
                    for (double s = m.arc(); s < m.arc() + 25 && s < route.length(); s += 1) {
                        slowest = Math.min(slowest, route.targetSpeedAt(s));
                    }
                    assertTrue(slowest > 2.5 && slowest < 9, m.instruction() + " at " + slowest * 3.6 + " km/h, "
                            + m.arc() + " of " + route.length() + " m");
                    turns++;
                }
            }
        }
        assertTrue(turns > 3, "routes have turns");
    }

    @Test
    @DisplayName("Cornering speed follows the lateral acceleration drivers accept")
    void cornerSpeedModel() {
        double limit = 100 / 3.6;
        double tight = Route.cornerSpeed(1 / 8.0, limit);      // 90-degree city corner
        double medium = Route.cornerSpeed(1 / 60.0, limit);
        double wide = Route.cornerSpeed(1 / 400.0, limit);     // fast bend
        assertEquals(Math.sqrt(Route.comfortLateral(tight) * 8), tight, 0.05);
        assertTrue(tight * 3.6 > 15 && tight * 3.6 < 20, "tight corner " + tight * 3.6 + " km/h");
        assertTrue(medium * 3.6 > 40 && medium * 3.6 < 50, "medium bend " + medium * 3.6 + " km/h");
        assertTrue(wide * 3.6 > 85, "wide bend " + wide * 3.6 + " km/h");
        assertTrue(Route.comfortLateral(5) > Route.comfortLateral(25), "less lateral g accepted at speed");
        assertEquals(limit, Route.cornerSpeed(0, limit), 1e-9);
    }

    @Test
    @DisplayName("The tracker follows progress along the route")
    void trackerProgress() {
        RoadGraph.Location start = startLocation();
        Place place = cityPlaces().get(0);
        Route route = build(start, place, route(PathFinder.aStar(), start, place, Set.of()));
        assertNotNull(route);
        RouteTracker tracker = new RouteTracker(route);
        tracker.update(route.x(0), route.y(0));
        double total = tracker.remainingDistance();
        int steps = Math.min(route.size() - 1, 120);
        for (int i = 0; i < steps; i++) {
            double h = route.headingAt(route.arc(i));
            tracker.update(route.x(i) - Math.sin(h) * 1.0, route.y(i) + Math.cos(h) * 1.0);
        }
        assertEquals(route.arc(steps - 1), tracker.arc(), 1.5);
        assertEquals(1.0, tracker.lateralError(), 0.15);
        assertTrue(tracker.remainingDistance() < total);
        assertTrue(tracker.remainingSeconds() > 0);
    }
}
