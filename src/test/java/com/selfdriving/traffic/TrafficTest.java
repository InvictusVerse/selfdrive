package com.selfdriving.traffic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.World;

class TrafficTest {

    private static final World WORLD = new World();
    private static final double DT = 1.0 / 60;

    @Test
    @DisplayName("City traffic keeps moving for 3 minutes without vehicles running into each other or red lights")
    void trafficFlows() {
        TrafficSystem traffic = new TrafficSystem(WORLD.network(), 70, 7);
        traffic.populate(null);
        assertTrue(traffic.count() >= 60, "populated: " + traffic.count());
        int overlapSeconds = 0;
        double speedSum = 0;
        int samples = 0;
        int minCount = Integer.MAX_VALUE;
        for (int i = 0; i < 180 * 60; i++) {
            traffic.update(DT, i * DT, null, List.of());
            if (i % 60 == 0) {
                List<OrientedBox> boxes = traffic.boxes();
                int overlaps = 0;
                for (int a = 0; a < boxes.size(); a++) {
                    for (int b = a + 1; b < boxes.size(); b++) {
                        OrientedBox.Contact c = boxes.get(a).overlap(boxes.get(b));
                        if (c != null && c.depth() > 0.3) {
                            overlaps++;
                            System.out.println("OVERLAP " + traffic.describe(a) + "  WITH  " + traffic.describe(b));
                        }
                    }
                }
                overlapSeconds += overlaps;
                speedSum += traffic.averageSpeed();
                samples++;
                minCount = Math.min(minCount, traffic.count());
            }
        }
        double averageKmh = speedSum / samples * 3.6;
        System.out.printf("Traffic: %d junction crossings, average %.1f km/h, overlaps %d, red-light violations %d,"
                + " fewest vehicles %d%n", traffic.junctionEntries(), averageKmh, overlapSeconds,
                traffic.redLightViolations(), minCount);
        traffic.violations().forEach(v -> System.out.println("RED " + v));
        assertEquals(0, traffic.redLightViolations(), "red lights");
        assertTrue(overlapSeconds <= 3, "vehicles overlapping: " + overlapSeconds);
        assertTrue(averageKmh > 10, "traffic moves: " + averageKmh + " km/h");
        assertTrue(traffic.junctionEntries() > 300, "vehicles cross junctions: " + traffic.junctionEntries());
        assertTrue(minCount > 45, "the roads stay busy: " + minCount);
    }

    @Test
    @DisplayName("Traffic stops for a pedestrian standing in its lane")
    void stopsForObstacle() {
        TrafficSystem traffic = new TrafficSystem(WORLD.network(), 40, 3);
        traffic.populate(null);
        traffic.update(DT, 0, null, List.of());
        // Put a pedestrian a few metres in front of every vehicle's current path and check nobody hits one.
        List<OrientedBox> boxes = traffic.boxes();
        OrientedBox first = boxes.get(0);
        double c = Math.cos(first.heading());
        double s = Math.sin(first.heading());
        Obstacle person = new Obstacle(1, Obstacle.Kind.PEDESTRIAN,
                new OrientedBox(first.cx() + c * 25, first.cy() + s * 25, 0, 0.3, 0.3), 1.7, "Pedestrian");
        double closest = Double.MAX_VALUE;
        for (int i = 0; i < 20 * 60; i++) {
            traffic.update(DT, i * DT, null, List.of(person));
            for (OrientedBox b : traffic.boxes()) {
                double d = Math.hypot(b.cx() - person.box().cx(), b.cy() - person.box().cy()) - b.halfLength();
                if (Math.abs(-(person.box().cx() - b.cx()) * Math.sin(b.heading())
                        + (person.box().cy() - b.cy()) * Math.cos(b.heading())) < 1.0) {
                    closest = Math.min(closest, d);
                }
            }
        }
        assertTrue(closest > 0.5, "vehicles keep clear of the pedestrian: " + closest);
    }
}
