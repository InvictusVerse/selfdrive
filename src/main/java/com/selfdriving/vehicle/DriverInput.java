package com.selfdriving.vehicle;

/**
 * Raw driver controls from two independent sources: the keyboard (keys held right now) and the
 * on-screen controls (pedals pressed, steering wheel dragged). Releasing a key never releases an
 * on-screen pedal and vice versa.
 *
 * <p>Written by the UI thread, read by the simulation thread, so every field is volatile.
 */
public final class DriverInput {

    private volatile boolean keyAccelerate;
    private volatile boolean keyBrake;
    private volatile boolean keyFullBrake;
    private volatile boolean keySteerLeft;
    private volatile boolean keySteerRight;

    private volatile boolean touchAccelerate;
    private volatile boolean touchBrake;
    /** Steering wheel position from the screen, -1..1, or NaN when nobody is holding it. */
    private volatile double touchSteer = Double.NaN;

    // ---- Combined view (read by the simulation) ------------------------------------------

    public boolean accelerate() {
        return keyAccelerate || touchAccelerate;
    }

    public boolean brake() {
        return keyBrake || touchBrake;
    }

    /** Emergency-style full brake (the pedal goes straight to 100 %). */
    public boolean fullBrake() {
        return keyFullBrake;
    }

    public boolean steerLeft() {
        return keySteerLeft;
    }

    public boolean steerRight() {
        return keySteerRight;
    }

    /** True while the on-screen steering wheel is being held. */
    public boolean isTouchSteering() {
        return !Double.isNaN(touchSteer);
    }

    /**
     * Where the driver wants the steering wheel: -1 (full right) .. +1 (full left).
     * The on-screen wheel, when held, wins over the keys.
     */
    public double steerTarget() {
        double touch = touchSteer;
        if (!Double.isNaN(touch)) {
            return touch;
        }
        return (keySteerLeft ? 1 : 0) - (keySteerRight ? 1 : 0);
    }

    /** True if the driver is touching the brake or steering (used to take over from autopilot). */
    public boolean isOverriding() {
        return brake() || fullBrake() || keySteerLeft || keySteerRight || isTouchSteering();
    }

    // ---- Keyboard ------------------------------------------------------------------------

    public void setAccelerate(boolean held) {
        keyAccelerate = held;
    }

    public void setBrake(boolean held) {
        keyBrake = held;
    }

    public void setFullBrake(boolean held) {
        keyFullBrake = held;
    }

    public void setSteerLeft(boolean held) {
        keySteerLeft = held;
    }

    public void setSteerRight(boolean held) {
        keySteerRight = held;
    }

    // ---- On-screen controls ------------------------------------------------------------------

    public void setTouchAccelerate(boolean pressed) {
        touchAccelerate = pressed;
    }

    public void setTouchBrake(boolean pressed) {
        touchBrake = pressed;
    }

    /** @param position -1..1, or NaN to let go of the wheel */
    public void setTouchSteer(double position) {
        touchSteer = Double.isNaN(position) ? Double.NaN : Math.max(-1, Math.min(1, position));
    }

    /** Releases everything (e.g. when the window loses focus). */
    public void releaseAll() {
        keyAccelerate = keyBrake = keyFullBrake = keySteerLeft = keySteerRight = false;
        touchAccelerate = touchBrake = false;
        touchSteer = Double.NaN;
    }
}
