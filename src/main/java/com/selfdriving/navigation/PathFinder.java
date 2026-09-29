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
     * Finds the fastest route to a junction (the end of any edge leading into it).
     *
     * @param goal destination junction
     */
    public Optional<Result> find(RoadGraph graph, RoadGraph.Edge start, double startArc, RoadGraph.Node goal,
                                 Set<Integer> closed) {
        return search(graph, start, startArc, -1, goal, closed);
    }

    /**
     * Finds the fastest route to a point on an edge.
     *
     * @param graph     road network
     * @param start     edge the car is on
     * @param startArc  how far along that edge the car already is, m
     * @param goal      edge the destination is on
     * @param goalArc   where on that edge, m
     * @param closed    ids of closed edges
     */
    public Optional<Result> find(RoadGraph graph, RoadGraph.Edge start, double startArc, RoadGraph.Edge goal,
                                 double goalArc, Set<Integer> closed) {
        if (start.id() == goal.id() && goalArc >= startArc + 10) {
            return Optional.of(new Result(List.of(start), (goalArc - startArc) / start.speedLimit(), 1));
        }
        return search(graph, start, startArc, goal.id(), goal.from(), closed);
    }

    /**
     * Best-first search over edges. The start edge may be entered a second time (after a loop),
     * which is how a destination just behind the car is reached; that second visit has its own key.
     *
     * @param goalEdge edge to reach (entered from its start), or -1 to stop at {@code goalNode}
     */
    private Optional<Result> search(RoadGraph graph, RoadGraph.Edge start, double startArc, int goalEdge,
                                    RoadGraph.Node goalNode, Set<Integer> closed) {
        record Entry(int key, RoadGraph.Edge edge, double cost, double estimate) {
        }
        double firstCost = Math.max(0, start.length() - startArc) / start.speedLimit();
        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.estimate(), b.estimate()));
        Map<Integer, Double> best = new HashMap<>();
        Map<Integer, Integer> cameFrom = new HashMap<>();

        int startKey = start.id();
        open.add(new Entry(startKey, start, firstCost, firstCost + heuristic(graph, start.to(), goalNode)));
        best.put(startKey, firstCost);
        int expanded = 0;
        while (!open.isEmpty()) {
            Entry current = open.poll();
            if (current.cost() > best.getOrDefault(current.key(), Double.MAX_VALUE) + 1e-9) {
                continue; // stale queue entry
            }
            expanded++;
            boolean done = goalEdge >= 0
                    ? current.edge().id() == goalEdge && current.key() != startKey
                    : current.edge().to().id() == goalNode.id();
            if (done) {
                return Optional.of(new Result(path(graph, cameFrom, current.key(), startKey), current.cost(), expanded));
            }
            for (RoadGraph.Edge next : graph.successors(current.edge())) {
                if (closed.contains(next.id())) {
                    continue; // closed roads (U-turns are never successors)
                }
                int key = next.id() == start.id() ? ~next.id() : next.id();
                if (key < 0 && current.key() < 0) {
                    continue;
                }
                double cost = current.cost() + next.travelTime();
                if (cost < best.getOrDefault(key, Double.MAX_VALUE)) {
                    best.put(key, cost);
                    cameFrom.put(key, current.key());
                    open.add(new Entry(key, next, cost, cost + heuristic(graph, next.to(), goalNode)));
                }
            }
        }
        return Optional.empty();
    }

    private static List<RoadGraph.Edge> path(RoadGraph graph, Map<Integer, Integer> cameFrom, int lastKey,
                                             int startKey) {
        List<RoadGraph.Edge> edges = new ArrayList<>();
        Integer key = lastKey;
        while (key != null) {
            edges.add(graph.edge(key < 0 ? ~key : key));
            if (key == startKey) {
                break;
            }
            key = cameFrom.get(key);
        }
        Collections.reverse(edges);
        return edges;
    }
}
