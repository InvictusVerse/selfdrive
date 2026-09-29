package com.selfdriving.world;

/**
 * Position and heading on the ground plane.
 *
 * @param x       east, m
 * @param y       north, m
 * @param heading rad, counter-clockwise from east
 */
public record Pose(double x, double y, double heading) {
}
