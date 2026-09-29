package com.selfdriving.vehicle;

/**
 * Raw driver controls: which keys are held right now.
 *
 * <p>Written by the UI thread, read by the simulation thread, so every field is volatile.
 */
public final class DriverInput {

    private volatile boolean accelerate;
    private volatile boolean brake;
    private volatile boolean fullBrake;
    private volatile boolean steerLeft;
    private volatile boolean steerRight;

    public boolean accelerate() {
        return accelerate;
    }

    public void setAccelerate(boolean held) {
        accelerate = held;
    }

    public boolean brake() {
        return brake;
    }

    public void setBrake(boolean held) {
        brake = held;
    }

    /** Emergency-style full brake (the pedal goes straight to 100 %). */
    public boolean fullBrake() {
        return fullBrake;
    }

    public void setFullBrake(boolean held) {
        fullBrake = held;
    }

    public boolean steerLeft() {
        return steerLeft;
    }

    public void setSteerLeft(boolean held) {
        steerLeft = held;
    }

    public boolean steerRight() {
        return steerRight;
    }

    public void setSteerRight(boolean held) {
        steerRight = held;
    }

    /** Releases everything (e.g. when the window loses focus). */
    public void releaseAll() {
        accelerate = brake = fullBrake = steerLeft = steerRight = false;
    }
}
