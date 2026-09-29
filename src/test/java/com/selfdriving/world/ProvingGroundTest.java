package com.selfdriving.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProvingGroundTest {

    @Test
    @DisplayName("Offsetting a straight line moves it sideways to the left")
    void offsetMovesLeft() {
        Polyline line = Polyline.of(false, new Point2(0, 0), new Point2(10, 0));
        Polyline left = line.offset(2);
        assertEquals(2, left.points().get(0).y(), 1e-9);
        assertEquals(2, left.points().get(1).y(), 1e-9);
    }

    @Test
    @DisplayName("Dashes follow the requested dash and gap lengths")
    void dashesHaveRightLength() {
        Polyline line = new Polyline(Polyline.straight(new Point2(0, 0), new Point2(90, 0), 7), false);
        List<Polyline> dashes = line.dashes(3, 6);
        assertEquals(10, dashes.size());
        for (Polyline dash : dashes) {
            assertEquals(3, dash.length(), 1e-6);
        }
        assertEquals(9, dashes.get(1).points().get(0).x(), 1e-6);
    }

    @Test
    @DisplayName("The circuit is a closed stadium loop of the expected length")
    void circuitLength() {
        Road circuit = new ProvingGround().roads().get(0);
        assertTrue(circuit.centre().closed());
        double expected = 2 * 700 + 2 * Math.PI * 150;
        assertEquals(expected, circuit.centre().length(), expected * 0.005);
    }

    @Test
    @DisplayName("The car starts in the right-hand lane of the main straight, facing east")
    void startPose() {
        Pose start = new ProvingGround().start();
        assertEquals(0, start.heading());
        assertTrue(start.y() < 0 && start.y() > -5.5);
    }
}
