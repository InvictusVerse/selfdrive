package com.selfdriving.autopilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.sensors.SensorReadings.DetectedObject;
import com.selfdriving.world.Obstacle;

class SafetyControllerTest {

    private static DetectedObject stoppedCar(double x, double y) {
        return new DetectedObject(7, Obstacle.Kind.CAR, x, y, 0, 2.3, 0.9, 0, 0, Math.hypot(x, y));
    }

    private static SafetyController.Assessment assess(SafetyController s, double speed, List<DetectedObject> objects) {
        return s.assess(true, 0, 0, 0, speed, 0, null, 0, objects, 1.0);
    }

    @Test
    @DisplayName("A stopped car far ahead gives no warning; closer it warns; closer still it brakes")
    void escalation() {
        double v = 20;
        assertFalse(assess(new SafetyController(), v, List.of(stoppedCar(120, 0))).warning());

        SafetyController.Assessment warn = assess(new SafetyController(), v, List.of(stoppedCar(50, 0)));
        assertTrue(warn.warning());
        assertFalse(warn.emergencyBraking());
        assertEquals((50 - 2.3 - 2.40 - 0.35) / v, warn.timeToCollision(), 0.06);

        SafetyController.Assessment brake = assess(new SafetyController(), v, List.of(stoppedCar(32, 0)));
        assertTrue(brake.emergencyBraking());
        assertEquals(7, brake.threatId());
    }

    @Test
    @DisplayName("Objects beside the path are ignored; a pedestrian walking into the path is not")
    void predictsCrossing() {
        DetectedObject parked = new DetectedObject(1, Obstacle.Kind.CAR, 20, 4, 0, 2.3, 0.9, 0, 0, 20);
        assertFalse(assess(new SafetyController(), 12, List.of(parked)).warning());

        DetectedObject walking = new DetectedObject(2, Obstacle.Kind.PEDESTRIAN, 20, 4, 0, 0.25, 0.25, 0, -1.5, 20);
        SafetyController.Assessment a = assess(new SafetyController(), 12, List.of(walking));
        assertTrue(a.warning(), "crossing pedestrian predicted");
        assertTrue(a.timeToCollision() < 2.0);
    }

    @Test
    @DisplayName("Braking holds until the car stops and the path is clear")
    void holdsUntilClear() {
        SafetyController s = new SafetyController();
        assertTrue(assess(s, 15, List.of(stoppedCar(22, 0))).emergencyBraking());
        assertTrue(assess(s, 0.1, List.of(stoppedCar(8, 0))).emergencyBraking(), "object still in front");
        assertFalse(assess(s, 0.1, List.of()).emergencyBraking(), "released once clear");
    }

    @Test
    @DisplayName("Lower grip means earlier emergency braking")
    void frictionMatters() {
        DetectedObject car = stoppedCar(45, 0);
        assertFalse(new SafetyController().assess(true, 0, 0, 0, 20, 0, null, 0, List.of(car), 1.0).emergencyBraking());
        assertTrue(new SafetyController().assess(true, 0, 0, 0, 20, 0, null, 0, List.of(car), 0.3).emergencyBraking());
    }

    @Test
    @DisplayName("A larger reaction allowance (setting) starts emergency braking earlier; values are clamped")
    void reactionAllowance() {
        // 20 m/s, about 31 m to impact: the default 0.25 s allowance needs 30.5 m, 0.5 s needs 35.5 m.
        assertFalse(assess(new SafetyController(), 20, List.of(stoppedCar(36, 0))).emergencyBraking());
        SafetyController cautious = new SafetyController();
        cautious.setReactionAllowance(0.5);
        assertTrue(assess(cautious, 20, List.of(stoppedCar(36, 0))).emergencyBraking());

        cautious.setReactionAllowance(5);
        assertEquals(0.8, cautious.reactionAllowance(), 1e-9);
        cautious.setReactionAllowance(0);
        assertEquals(0.1, cautious.reactionAllowance(), 1e-9);
    }

    @Test
    @DisplayName("PID: proportional, integral and derivative terms")
    void pid() {
        PidController pid = new PidController(2, 1, 0.5, 10);
        assertEquals(2 * 1 + 1 * 0.1 + 0, pid.update(1, 0.1), 1e-9);
        assertEquals(2 * 2 + 1 * 0.3 + 0.5 * 10, pid.update(2, 0.1), 1e-9);
        PidController clamped = new PidController(0, 1, 0, 0.5);
        for (int i = 0; i < 100; i++) {
            clamped.update(10, 0.1);
        }
        assertEquals(0.5, clamped.integral(), 1e-9, "anti-windup");
    }
}
