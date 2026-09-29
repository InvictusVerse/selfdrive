package com.selfdriving.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.world.City;
import com.selfdriving.world.Place;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

class NavigationTest {

    private static final World WORLD = new World();
    private static final RoadGraph GRAPH = RoadGraph.build(WORLD);

    private static RoadGraph.Location startLocation() {
        Pose start = WORLD.start();
        return GRAPH.locate(start.x(), start.y(), start.heading()).orElseThrow();
    }

    private static RoadGraph.Node place(String name) {
        Place place = WORLD.places().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow();
        return GRAPH.nodeAt(place.location()).orElseThrow();
    }

    @Test
    @DisplayName("The network has every junction and both directions of two-way streets")
    void graphStructure() {
        assertEquals(3 + City.COLUMNS.length * City.ROWS.length, GRAPH.nodes().size());
        int streets = WORLD.city().streets().size();
        // 2 circuit halves (one-way) + skidpad access (2) + connector (2) + streets (2 each)
        assertEquals(2 + 2 + 2 + 2 * streets, GRAPH.edges().size());
    }

    @Test
    @DisplayName("The start position is found on the circuit, driving direction")
    void locatesStart() {
        RoadGraph.Location location = startLocation();
        assertEquals("Circuit", location.edge().roadName());
        assertTrue(location.distance() < 0.5, "car starts in the lane centre");
    }

    @Test
    @DisplayName("A* and Dijkstra find equally fast routes; A* examines fewer roads")
    void aStarMatchesDijkstra() {
        RoadGraph.Location start = startLocation();
        for (Place place : WORLD.places()) {
            RoadGraph.Node goal = GRAPH.nodeAt(place.location()).orElseThrow();
            PathFinder.Result a = PathFinder.aStar().find(GRAPH, start.edge(), start.arc(), goal, Set.of()).orElseThrow();
            PathFinder.Result d = PathFinder.dijkstra().find(GRAPH, start.edge(), start.arc(), goal, Set.of()).orElseThrow();
            assertEquals(d.seconds(), a.seconds(), 1e-6, place.name());
            assertTrue(a.expanded() <= d.expanded(), place.name() + ": A* " + a.expanded() + " vs " + d.expanded());
        }
    }

    @Test
    @DisplayName("Routes never make a U-turn at a junction")
    void noUTurns() {
        RoadGraph.Location start = startLocation();
        PathFinder.Result result = PathFinder.aStar()
                .find(GRAPH, start.edge(), start.arc(), place("West Gate"), Set.of()).orElseThrow();
        List<RoadGraph.Edge> edges = result.edges();
        for (int i = 1; i < edges.size(); i++) {
            assertNotEquals(edges.get(i - 1).reverseId(), edges.get(i).id());
        }
    }

    @Test
    @DisplayName("Closing a road on the route gives a different, slower route")
    void closedRoadForcesDetour() {
        RoadGraph.Location start = startLocation();
        RoadGraph.Node goal = place("Central Station");
        PathFinder.Result open = PathFinder.aStar().find(GRAPH, start.edge(), start.arc(), goal, Set.of()).orElseThrow();

        RoadGraph.Edge cityEdge = open.edges().stream().filter(e -> e.roadName().endsWith("Avenue")).findFirst()
                .orElseThrow();
        Set<Integer> closed = new HashSet<>(Set.of(cityEdge.id(), cityEdge.reverseId()));
        PathFinder.Result detour = PathFinder.aStar().find(GRAPH, start.edge(), start.arc(), goal, closed).orElseThrow();

        assertTrue(detour.edges().stream().noneMatch(e -> closed.contains(e.id())));
        assertTrue(detour.seconds() > open.seconds());
    }

    @Test
    @DisplayName("A route is a smooth, continuous path with safe speeds and directions")
    void routeGeometryAndSpeeds() {
        RoadGraph.Location start = startLocation();
        PathFinder.Result result = PathFinder.aStar()
                .find(GRAPH, start.edge(), start.arc(), place("Market Square"), Set.of()).orElseThrow();
        Route route = Route.build(result.edges(), start.arc(), "Market Square");

        assertEquals(WORLD.start().x(), route.x(0), 0.5);
        assertEquals(WORLD.start().y(), route.y(0), 0.5);
        for (int i = 1; i < route.size(); i++) {
            double gap = Math.hypot(route.x(i) - route.x(i - 1), route.y(i) - route.y(i - 1));
            assertTrue(gap < Route.SPACING + 0.2, "gap " + gap + " at " + i);
        }
        for (int i = 0; i < route.size(); i++) {
            double arc = route.arc(i);
            assertTrue(route.targetSpeedAt(arc) <= route.speedLimitAt(arc) + 0.2);
        }
        assertEquals(0, route.targetSpeedAt(route.length()), 1e-9);

        assertTrue(route.maneuvers().stream().anyMatch(m -> m.instruction().equals("Turn right onto Connector")));
        for (Route.Maneuver m : route.maneuvers()) {
            if (m.type() != Route.Maneuver.Type.ARRIVE) {
                double cornerSpeed = route.targetSpeedAt(m.arc() + 6);
                assertTrue(cornerSpeed < 10, m.instruction() + " at " + cornerSpeed + " m/s");
            }
        }
        assertEquals("Arrive at Market Square", route.maneuvers().get(route.maneuvers().size() - 1).instruction());
    }

    @Test
    @DisplayName("The tracker follows progress along the route")
    void trackerProgress() {
        RoadGraph.Location start = startLocation();
        PathFinder.Result result = PathFinder.aStar()
                .find(GRAPH, start.edge(), start.arc(), place("Tech Park"), Set.of()).orElseThrow();
        Route route = Route.build(result.edges(), start.arc(), "Tech Park");
        RouteTracker tracker = new RouteTracker(route);
        tracker.update(route.x(0), route.y(0));
        double total = tracker.remainingDistance();
        for (int i = 0; i < 200; i++) {
            tracker.update(route.x(i), route.y(i) + 1.0);
        }
        assertEquals(route.arc(199), tracker.arc(), 1.0);
        assertEquals(1.0, tracker.lateralError(), 0.05);
        assertTrue(tracker.remainingDistance() < total);
        assertTrue(tracker.remainingSeconds() > 0);
    }
}
