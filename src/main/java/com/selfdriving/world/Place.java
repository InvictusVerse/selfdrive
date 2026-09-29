package com.selfdriving.world;

/**
 * A named destination the driver can pick. It sits on a road junction, so a route always
 * ends on the road network.
 *
 * @param name     display name
 * @param location junction position
 */
public record Place(String name, Point2 location) {

    @Override
    public String toString() {
        return name;
    }
}
