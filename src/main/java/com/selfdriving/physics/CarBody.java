package com.selfdriving.physics;

/** Outer dimensions of the car body, measured from the centre of gravity. */
public final class CarBody {

    /** Front bumper ahead of the centre of gravity, m. */
    public static final double FRONT = 2.40;

    /** Rear bumper behind the centre of gravity, m. */
    public static final double REAR = 2.32;

    /** Half the body width, m. */
    public static final double HALF_WIDTH = 0.93;

    private CarBody() {
    }

    /** Half the body length, m. */
    public static double halfLength() {
        return (FRONT + REAR) / 2;
    }

    /** How far the body's centre sits ahead of the centre of gravity, m. */
    public static double centreOffset() {
        return (FRONT - REAR) / 2;
    }
}
