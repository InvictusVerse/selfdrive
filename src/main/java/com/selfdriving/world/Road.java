package com.selfdriving.world;

/**
 * A stretch of road.
 *
 * @param name   display name
 * @param centre centre line
 * @param width  total width, m
 * @param lanes  number of lanes
 */
public record Road(String name, Polyline centre, double width, int lanes) {

    /** Width of one lane, m. */
    public double laneWidth() {
        return width / lanes;
    }
}
