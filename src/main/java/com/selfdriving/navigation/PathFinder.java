package com.selfdriving.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Fastest-route search over the road graph.
 *
 * <p>The search runs over <em>edges</em> rather than junctions: the state is "which road am I
 * on", which makes it easy to forbid U-turns at junctions and to start in the middle of a road.
 * Cost is travel time at the speed limit; closed roads are skipped.
 *
 * <p>Two strategies share the same search: {@link #aStar()} uses a straight-line-distance
 * heuristic (distance / highest speed limit, which never overestimates, so the result is
 * still optimal), {@link #dijkstra()} uses none. Both find the same route; A* expands fewer edges.
 */
public abstract class PathFinder {

    /**
     * A found route.
     *
     * @param edges    edges to drive, starting with the current one
     * @param seconds  estimated travel time at the speed limits, s
     * @param expanded how many edges the search examined (to compare algorithms)
     */
    public record Result(List<RoadGraph.Edge> edges, double seconds, int expanded) {
    }

    public static PathFinder aStar() {
        return new PathFinder() {
            @Override
            protected double heuristic(RoadGraph graph, RoadGraph.Node from, RoadGraph.Node goal) {
                return from.position().distanceTo(goal.position()) / graph.maxSpeedLimit();
            }

            @Override
            public String name() {
                return "A*";
            }
        };
    }

    public static PathFinder dijkstra() {
        return new PathFinder() {
            @Override
            protected double heuristic(RoadGraph graph, RoadGraph.Node from, RoadGraph.Node goal) {
                return 0;
            }

            @Override
            public String name() {
                return "Dijkstra";
            }
        };
    }

    /** Lower bound of the remaining travel time from a junction to the goal, s. */
    protected abstract double heuristic(RoadGraph graph, RoadGraph.Node from, RoadGraph.Node goal);

    public abstract String name();

    /**
     * Finds the fastest route.
     *
     * @param graph     road network
     * @param start     edge the car is on
     * @param startArc  how far along that edge the car already is, m
     * @param goal      destination junction
     * @param closed    ids of closed edges
     */
    public Optional<Result> find(RoadGraph graph, RoadGraph.Edge start, double startArc, RoadGraph.Node goal,
                                 Set<Integer> closed) {
        record Entry(RoadGraph.Edge edge, double cost, double estimate) {
        }
        double firstCost = Math.max(0, start.length() - startArc) / start.speedLimit();
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.estimate(), b.estimate()));
        Map<Integer, Double> best = new HashMap<>();
        Map<Integer, RoadGraph.Edge> cameFrom = new HashMap<>();

        open.add(new Entry(start, firstCost, firstCost + heuristic(graph, start.to(), goal)));
        best.put(start.id(), firstCost);
        int expanded = 0;
        while (!open.isEmpty()) {
            Entry current = open.poll();
            if (current.cost() > best.getOrDefault(current.edge().id(), Double.MAX_VALUE) + 1e-9) {
                continue; // stale queue entry
            }
            expanded++;
            if (current.edge().to().id() == goal.id()) {
                return Optional.of(new Result(path(cameFrom, current.edge()), current.cost(), expanded));
            }
            for (RoadGraph.Edge next : graph.outgoing(current.edge().to())) {
                if (next.id() == current.edge().reverseId() || closed.contains(next.id())) {
                    continue; // no U-turns at junctions, no closed roads
                }
                double cost = current.cost() + next.travelTime();
                if (cost < best.getOrDefault(next.id(), Double.MAX_VALUE)) {
                    best.put(next.id(), cost);
                    cameFrom.put(next.id(), current.edge());
                    open.add(new Entry(next, cost, cost + heuristic(graph, next.to(), goal)));
                }
            }
        }
        return Optional.empty();
    }

    private static List<RoadGraph.Edge> path(Map<Integer, RoadGraph.Edge> cameFrom, RoadGraph.Edge last) {
        List<RoadGraph.Edge> edges = new ArrayList<>();
        RoadGraph.Edge edge = last;
        while (edge != null) {
            edges.add(edge);
            edge = cameFrom.get(edge.id());
        }
        Collections.reverse(edges);
        return edges;
    }
}
