package com.selfdriving.vehicle;

import com.selfdriving.physics.Gear;

/**
 * Exterior lighting: indicators with self-cancelling, hazard lights, headlights (off, automatic,
 * on) with main beam and flash, and the brake and reversing lights.
 *
 * <p>Rules follow common vehicle regulations (UN ECE R48): indicators flash at 90 per minute,
 * the hazard switch flashes both sides, the main beam only works with the headlights on
 * (except the momentary flash), and brake lights also come on under strong regenerative
 * braking, as on electric cars.
 *
 * <p>Owned by the simulation thread.
 */
public final class Lights {

    /** Indicator lever. */
    public enum Indicator { OFF, LEFT, RIGHT }

    /** Headlight switch. */
    public enum HeadlightMode {
        OFF("Off"), AUTO("Auto"), ON("On");

        private final String label;

        HeadlightMode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public HeadlightMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** One on-off flash cycle, s (90 flashes per minute). */
    public static final double FLASH_PERIOD = 60.0 / 90;

    /** Steering past this (share of full lock) arms the self-cancel. */
    private static final double CANCEL_ARM = 0.22;

    /** Steering back below this cancels an armed indicator. */
    private static final double CANCEL_RELEASE = 0.06;

    /** Deceleration above which regeneration lights the brake lamps, m/s^2. */
    private static final double REGEN_BRAKE_LIGHT_DECEL = 1.3;

    private Indicator driverIndicator = Indicator.OFF;
    private Indicator autoIndicator = Indicator.OFF;
    private boolean hazard;
    private HeadlightMode mode = HeadlightMode.AUTO;
    private boolean mainBeam;
    private boolean flash;
    private boolean armed;
    private double flashClock;
    private boolean wasFlashing;
    private LightState state = LightState.off();

    // ---- driver switches ------------------------------------------------------------------

    /** Pushes the indicator lever; pushing the same way again returns it to off. */
    public void toggleIndicator(Indicator side) {
        driverIndicator = driverIndicator == side ? Indicator.OFF : side;
        armed = false;
    }

    public void setIndicator(Indicator side) {
        driverIndicator = side;
        armed = false;
    }

    public Indicator driverIndicator() {
        return driverIndicator;
    }

    public void toggleHazard() {
        hazard = !hazard;
    }

    public void setHazard(boolean on) {
        hazard = on;
    }

    public void setHeadlightMode(HeadlightMode mode) {
        this.mode = mode;
    }

    public HeadlightMode headlightMode() {
        return mode;
    }

    /** Main beam on/off (the "dipper" switch). */
    public void toggleMainBeam() {
        mainBeam = !mainBeam;
    }

    public void setMainBeam(boolean on) {
        mainBeam = on;
    }

    /** Momentary main-beam flash, held for as long as the lever is pulled. */
    public void setFlash(boolean on) {
        flash = on;
    }

    /** Indicator requested by the autopilot (turns and lane changes); OFF hands it back to the lever. */
    public void setAutoIndicator(Indicator side) {
        autoIndicator = side;
    }

    /** All switches back to their defaults. */
    public void reset() {
        driverIndicator = Indicator.OFF;
        autoIndicator = Indicator.OFF;
        hazard = false;
        mainBeam = false;
        flash = false;
        armed = false;
        state = LightState.off();
    }

    // ---- simulation -----------------------------------------------------------------------

    /**
     * Advances the flash timer, self-cancels the indicator and works out which lamps are lit.
     *
     * @param steer       steering wheel position, -1 (right) .. +1 (left)
     * @param brake       brake pedal 0..1
     * @param accel       longitudinal acceleration, m/s^2
     * @param speed       forward speed, m/s
     * @param gear        drive selector
     * @param dark        it is dark outside
     */
    public LightState update(double dt, double steer, double brake, double accel, double speed, Gear gear,
                             boolean dark) {
        selfCancel(steer);
        Indicator indicator = autoIndicator != Indicator.OFF ? autoIndicator : driverIndicator;
        boolean flashing = hazard || indicator != Indicator.OFF;
        if (flashing && !wasFlashing) {
            flashClock = 0; // the first flash starts at once
        }
        wasFlashing = flashing;
        flashClock = flashing ? (flashClock + dt) % FLASH_PERIOD : 0;
        boolean blinkOn = flashing && flashClock < FLASH_PERIOD / 2;

        boolean headlights = mode == HeadlightMode.ON || (mode == HeadlightMode.AUTO && dark);
        boolean high = flash || (headlights && mainBeam);
        boolean brakeLights = brake > 0.02 || (accel < -REGEN_BRAKE_LIGHT_DECEL && Math.abs(speed) > 0.5);
        state = new LightState(indicator, hazard, blinkOn, mode, headlights, high, headlights, brakeLights,
                gear == Gear.REVERSE, dark);
        return state;
    }

    public LightState state() {
        return state;
    }

    /** Like a real column switch: steering into the turn arms it, straightening up cancels it. */
    private void selfCancel(double steer) {
        if (driverIndicator == Indicator.OFF) {
            armed = false;
            return;
        }
        double into = driverIndicator == Indicator.LEFT ? steer : -steer;
        if (into > CANCEL_ARM) {
            armed = true;
        } else if (armed && into < CANCEL_RELEASE) {
            driverIndicator = Indicator.OFF;
            armed = false;
        }
    }
}
