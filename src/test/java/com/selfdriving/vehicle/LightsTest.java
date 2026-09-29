package com.selfdriving.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.selfdriving.physics.Gear;

class LightsTest {

    private static final double DT = 1.0 / 120;

    private static LightState run(Lights lights, double seconds, double steer, boolean dark) {
        LightState s = lights.state();
        for (int i = 0; i < Math.round(seconds / DT); i++) {
            s = lights.update(DT, steer, 0, 0, 10, Gear.DRIVE, dark);
        }
        return s;
    }

    @Test
    void indicatorFlashesAtNinetyPerMinute() {
        Lights lights = new Lights();
        lights.toggleIndicator(Lights.Indicator.LEFT);
        int flashes = 0;
        boolean was = false;
        for (int i = 0; i < 120 * 10; i++) {
            LightState s = lights.update(DT, 0, 0, 0, 10, Gear.DRIVE, false);
            if (s.leftLit() && !was) {
                flashes++;
            }
            assertFalse(s.rightLit());
            was = s.leftLit();
        }
        assertEquals(15, flashes, 1, "90 flashes per minute = 15 in 10 s");
    }

    @Test
    void indicatorCancelsAfterTheTurn() {
        Lights lights = new Lights();
        lights.toggleIndicator(Lights.Indicator.RIGHT);
        run(lights, 1, -0.05, false);
        assertEquals(Lights.Indicator.RIGHT, lights.state().indicator(), "small corrections keep it on");
        run(lights, 1, -0.5, false); // steer right into the turn
        assertEquals(Lights.Indicator.RIGHT, lights.state().indicator());
        run(lights, 0.5, 0, false); // straighten up
        assertEquals(Lights.Indicator.OFF, lights.state().indicator());

        lights.toggleIndicator(Lights.Indicator.LEFT);
        run(lights, 1, -0.5, false); // steering the other way does not cancel it
        run(lights, 0.5, 0, false);
        assertEquals(Lights.Indicator.LEFT, lights.state().indicator());
    }

    @Test
    void hazardFlashesBothSides() {
        Lights lights = new Lights();
        lights.toggleHazard();
        LightState s = lights.update(DT, 0, 0, 0, 0, Gear.PARK, false);
        assertTrue(s.leftLit() && s.rightLit());
    }

    @Test
    void automaticHeadlightsAndMainBeam() {
        Lights lights = new Lights();
        lights.toggleMainBeam();
        LightState day = run(lights, 0.1, 0, false);
        assertFalse(day.lowBeam(), "Auto: off in daylight");
        assertFalse(day.highBeam(), "main beam needs the headlights on");
        LightState night = run(lights, 0.1, 0, true);
        assertTrue(night.lowBeam() && night.highBeam() && night.tailLights());

        lights.setHeadlightMode(Lights.HeadlightMode.OFF);
        lights.setFlash(true);
        LightState flash = run(lights, 0.1, 0, true);
        assertTrue(flash.highBeam(), "flashing works with the headlights off");
        assertFalse(flash.lowBeam());
    }

    @Test
    void brakeAndReverseLights() {
        Lights lights = new Lights();
        assertTrue(lights.update(DT, 0, 0.3, 0, 10, Gear.DRIVE, false).brakeLights(), "brake pedal");
        assertTrue(lights.update(DT, 0, 0, -2.0, 10, Gear.DRIVE, false).brakeLights(), "strong regeneration");
        assertFalse(lights.update(DT, 0, 0, -0.5, 10, Gear.DRIVE, false).brakeLights(), "gentle lift-off");
        assertTrue(lights.update(DT, 0, 0, 0, -1, Gear.REVERSE, false).reverseLights());
    }

    @Test
    void autopilotIndicatorOverridesTheLever() {
        Lights lights = new Lights();
        lights.setAutoIndicator(Lights.Indicator.LEFT);
        assertEquals(Lights.Indicator.LEFT, lights.update(DT, 0, 0, 0, 10, Gear.DRIVE, false).indicator());
        lights.setAutoIndicator(Lights.Indicator.OFF);
        assertEquals(Lights.Indicator.OFF, lights.update(DT, 0, 0, 0, 10, Gear.DRIVE, false).indicator());
    }
}
