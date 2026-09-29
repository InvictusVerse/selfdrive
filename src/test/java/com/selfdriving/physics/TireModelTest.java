package com.selfdriving.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TireModelTest {

    private static final double LOAD = 4000;
    private static final double RADIUS = 0.34;

    @Test
    @DisplayName("A freely rolling tyre produces no force")
    void freeRollingHasNoForce() {
        double v = 20;
        TireModel.Force f = TireModel.compute(v, 0, v / RADIUS, RADIUS, LOAD, Surface.DRY);
        assertEquals(0, f.longitudinal(), 1e-6);
        assertEquals(0, f.lateral(), 1e-6);
    }

    @Test
    @DisplayName("Peak force equals mu times load at the peak slip")
    void peakForceIsMuTimesLoad() {
        for (Surface surface : Surface.values()) {
            double v = 20;
            double omega = v * (1 - surface.peakSlip()) / RADIUS;
            TireModel.Force f = TireModel.compute(v, 0, omega, RADIUS, LOAD, surface);
            assertEquals(-surface.friction() * LOAD, f.longitudinal(), LOAD * 0.01, surface.name());
        }
    }

    @Test
    @DisplayName("A locked wheel on a dry road still gives about 90 % of peak grip, backwards")
    void lockedWheelSlides() {
        TireModel.Force f = TireModel.compute(20, 0, 0, RADIUS, LOAD, Surface.DRY);
        double ratio = -f.longitudinal() / LOAD;
        assertTrue(ratio > 0.85 && ratio < 0.95, "locked ratio " + ratio);
        assertEquals(1.0, f.slip(), 1e-9);
    }

    @Test
    @DisplayName("Cornering force opposes sideways sliding")
    void lateralForceOpposesSlide() {
        TireModel.Force left = TireModel.compute(20, 1, 20 / RADIUS, RADIUS, LOAD, Surface.DRY);
        TireModel.Force right = TireModel.compute(20, -1, 20 / RADIUS, RADIUS, LOAD, Surface.DRY);
        assertTrue(left.lateral() < 0);
        assertEquals(-left.lateral(), right.lateral(), 1e-9);
    }

    @Test
    @DisplayName("Braking hard leaves less grip for cornering (friction circle)")
    void combinedSlipReducesCornering() {
        double v = 20;
        TireModel.Force cornering = TireModel.compute(v, 1.0, v / RADIUS, RADIUS, LOAD, Surface.DRY);
        TireModel.Force both = TireModel.compute(v, 1.0, v * 0.85 / RADIUS, RADIUS, LOAD, Surface.DRY);
        assertTrue(Math.abs(both.lateral()) < Math.abs(cornering.lateral()));
        assertTrue(Math.hypot(both.longitudinal(), both.lateral()) <= Surface.DRY.friction() * LOAD + 1e-6);
    }

    @Test
    @DisplayName("No load, no force")
    void unloadedTyreHasNoForce() {
        TireModel.Force f = TireModel.compute(20, 2, 0, RADIUS, 0, Surface.DRY);
        assertEquals(0, f.longitudinal());
        assertEquals(0, f.lateral());
    }
}
