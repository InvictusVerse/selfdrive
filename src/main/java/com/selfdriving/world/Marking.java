package com.selfdriving.world;

/**
 * Paint on the road.
 *
 * @param line  where the paint runs (a solid line, or one piece of a dashed line)
 * @param width paint width, m
 * @param kind  what the paint means
 */
public record Marking(Polyline line, double width, Kind kind) {

    public enum Kind {
        /** Solid line along the road edge. */
        EDGE,
        /** Dashed line between lanes. */
        LANE,
        /** Line across the road (start line, distance markers). */
        TRANSVERSE
    }
}
