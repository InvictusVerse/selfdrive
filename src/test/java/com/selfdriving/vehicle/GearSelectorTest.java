package com.selfdriving.vehicle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.selfdriving.physics.Gear;

class GearSelectorTest {

    @Test
    @DisplayName("Starts in Park")
    void startsInPark() {
        assertEquals(Gear.PARK, new GearSelector().gear());
    }

    @Test
    @DisplayName("Leaving Park needs the brake")
    void leavingParkNeedsBrake() {
        GearSelector selector = new GearSelector();
        GearSelector.Result result = selector.request(Gear.DRIVE, 0, 0);
        assertFalse(result.accepted());
        assertEquals(Gear.PARK, selector.gear());

        assertTrue(selector.request(Gear.DRIVE, 0, 0.5).accepted());
        assertEquals(Gear.DRIVE, selector.gear());
    }

    @Test
    @DisplayName("Park is refused while moving")
    void parkNeedsStandstill() {
        GearSelector selector = drivingSelector();
        assertFalse(selector.request(Gear.PARK, 10, 0).accepted());
        assertTrue(selector.request(Gear.PARK, 0.1, 0).accepted());
    }

    @Test
    @DisplayName("Drive to Reverse is refused at speed")
    void directionChangeNeedsLowSpeed() {
        GearSelector selector = drivingSelector();
        assertFalse(selector.request(Gear.REVERSE, 8, 0).accepted());
        assertEquals(Gear.DRIVE, selector.gear());
        assertTrue(selector.request(Gear.REVERSE, 0.5, 0).accepted());
        assertFalse(selector.request(Gear.DRIVE, -5, 0).accepted());
    }

    @Test
    @DisplayName("Neutral is always allowed")
    void neutralAlwaysAllowed() {
        GearSelector selector = drivingSelector();
        assertTrue(selector.request(Gear.NEUTRAL, 30, 0).accepted());
        assertEquals(Gear.NEUTRAL, selector.gear());
    }

    private static GearSelector drivingSelector() {
        GearSelector selector = new GearSelector();
        selector.request(Gear.DRIVE, 0, 1);
        return selector;
    }
}
