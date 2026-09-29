package com.selfdriving.sensors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.physics.VehicleModel;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.CollisionSystem;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;

class SensorTest {

    private static Obstacle box(int id, Obstacle.Kind kind, double x, double y, double halfLength, double halfWidth) {
        return new Obstacle(id, kind, new OrientedBox(x, y, 0, halfLength, halfWidth), 1.5, kind.name());
    }

    @Test
    @DisplayName("A ray enters a box at the right distance, and misses when aimed away")
    void rayBoxIntersection() {
        OrientedBox b = new OrientedBox(10, 0, 0, 1, 1);
        assertEquals(9, b.rayDistance(0, 0, 1, 0, 100), 1e-9);
        assertEquals(Double.POSITIVE_INFINITY, b.rayDistance(0, 0, -1, 0, 100));
        assertEquals(Double.POSITIVE_INFINITY, b.rayDistance(0, 5, 1, 0, 100));

        OrientedBox rotated = new OrientedBox(10, 0, Math.PI / 4, 1, 1);
        assertEquals(10 - Math.sqrt(2), rotated.rayDistance(0, 0, 1, 0, 100), 1e-9);
    }

    @Test
    @DisplayName("Overlap test finds touching boxes and the direction to separate them")
    void boxOverlap() {
        OrientedBox a = new OrientedBox(0, 0, 0, 2, 1);
        OrientedBox b = new OrientedBox(3.5, 0, 0, 2, 1);
        OrientedBox.Contact contact = a.overlap(b);
        assertNotNull(contact);
        assertEquals(-1, contact.nx(), 1e-9);
        assertEquals(0.5, contact.depth(), 1e-9);
        assertNull(a.overlap(new OrientedBox(5, 0, 0, 2, 1)));
        assertNull(a.overlap(new OrientedBox(0, 2.5, Math.PI / 2, 0.5, 0.5)));
    }

    @Test
    @DisplayName("Lidar sees objects all around; radar measures range and closing speed ahead")
    void lidarAndRadar() {
        Obstacle ahead = box(1, Obstacle.Kind.CAR, 40, 0, 2.3, 0.9);
        Obstacle behind = box(2, Obstacle.Kind.PEDESTRIAN, -20, 0, 0.25, 0.25);
        Obstacle building = box(3, Obstacle.Kind.BUILDING, 0, 30, 10, 5);
        SensorSuite sensors = new SensorSuite();
        SensorReadings r = sensors.scan(0, 0, 0, 20, 0, List.of(ahead, behind, building));

        assertEquals(Lidar.RAYS, r.lidarRanges().length);
        assertEquals(40 - 2.3, r.lidarRanges()[0], 0.2);
        assertTrue(r.detected().stream().anyMatch(d -> d.id() == 1));
        assertTrue(r.detected().stream().anyMatch(d -> d.id() == 2), "pedestrian behind is seen");
        assertTrue(r.detected().stream().noneMatch(d -> d.id() == 3), "buildings are map data, not objects");

        assertNotNull(r.radar());
        assertEquals(1, r.radar().obstacleId());
        assertEquals(40 - 2.3 - 2.38, r.radar().range(), 0.05);
        assertEquals(20, r.radar().closingSpeed(), 0.5);
    }

    @Test
    @DisplayName("Parking sensors measure short distances, and a failed lidar reports nothing")
    void ultrasonicAndFailures() {
        Obstacle wall = box(1, Obstacle.Kind.BARRIER, 4.0, 0, 0.5, 3);
        SensorSuite sensors = new SensorSuite();
        SensorReadings r = sensors.scan(0, 0, 0, 0, 0, List.of(wall));
        assertEquals(4.0 - 0.5 - 2.38, r.ultrasonic()[1], 0.05);
        assertTrue(Float.isNaN(r.ultrasonic()[5]), "nothing behind");

        sensors.setLidarWorking(false);
        SensorReadings failed = sensors.scan(0, 0, 0, 0, 0, List.of(wall));
        assertTrue(Float.isNaN(failed.lidarRanges()[0]));
        assertTrue(!failed.lidarWorking());
        assertNotNull(failed.radar(), "radar still works");
    }

    @Test
    @DisplayName("Driving into a wall stops the car instead of passing through it")
    void collisionStopsTheCar() {
        VehicleModel car = new VehicleModel(VehicleParams.electricSedan());
        car.reset(0, 0, 0);
        car.setForwardSpeed(10);
        Obstacle wall = box(9, Obstacle.Kind.BARRIER, 10, 0, 0.5, 5);
        CollisionSystem collisions = new CollisionSystem();
        boolean hit = false;
        for (int i = 0; i < 240; i++) {
            car.step(1.0 / 120, new com.selfdriving.physics.VehicleInputs(0, 0, 0, com.selfdriving.physics.Gear.NEUTRAL),
                    com.selfdriving.physics.Surface.DRY);
            hit |= !collisions.resolve(car, List.of(wall)).isEmpty();
        }
        assertTrue(hit);
        double front = car.x() + com.selfdriving.physics.CarBody.FRONT;
        assertTrue(front <= 9.5 + 0.05, "car front " + front + " stays out of the wall");
        assertTrue(car.forwardSpeed() < 2);
    }
}
