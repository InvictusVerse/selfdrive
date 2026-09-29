package com.selfdriving.world;

import java.util.List;

/**
 * A road as drawn on a map, before it becomes lanes and junctions: a centre line whose points
 * carry node ids (roads that share a node id meet there).
 *
 * @param name          road name for directions
 * @param rank          importance, used for right of way (higher wins): 6 trunk .. 1 living street
 * @param points        centre line in the direction of travel (for one-way roads)
 * @param nodes         node id for each point
 * @param oneway        traffic only in the drawn direction
 * @param lanesForward  lanes in the drawn direction
 * @param lanesBackward lanes against it (0 for one-way roads)
 * @param laneWidth     m
 * @param speedLimit    m/s
 * @param roundabout    part of a roundabout (traffic on it has right of way)
 * @param rendered      drawn as asphalt by the world model (the proving ground draws its own)
 */
public record RoadSource(String name, int rank, List<Point2> points, long[] nodes, boolean oneway,
                         int lanesForward, int lanesBackward, double laneWidth, double speedLimit,
                         boolean roundabout, boolean rendered) {

    public RoadSource {
        points = List.copyOf(points);
        if (points.size() != nodes.length || points.size() < 2) {
            throw new IllegalArgumentException("A road needs at least two points, each with a node id");
        }
    }

    /** Total paved width, m. */
    public double width() {
        return (lanesForward + lanesBackward) * laneWidth;
    }
}
