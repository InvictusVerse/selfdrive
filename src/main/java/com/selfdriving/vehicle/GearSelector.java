package com.selfdriving.vehicle;

import com.selfdriving.physics.Gear;

/**
 * Drive selector with the same safety rules as a real electric car:
 * <ul>
 *   <li>leaving Park needs the brake pressed,</li>
 *   <li>Park needs the car stopped,</li>
 *   <li>switching between Drive and Reverse needs (almost) walking pace,</li>
 *   <li>Neutral is always allowed.</li>
 * </ul>
 */
public final class GearSelector {

    /** Largest speed at which Park can be engaged, m/s. */
    public static final double PARK_MAX_SPEED = 0.5;

    /** Largest speed at which the direction of travel may be changed, m/s. */
    public static final double DIRECTION_CHANGE_MAX_SPEED = 1.5;

    /** Brake pedal needed to shift out of Park. */
    public static final double BRAKE_TO_SHIFT = 0.2;

    /** Outcome of a shift request. */
    public record Result(boolean accepted, String message) {

        static Result ok() {
            return new Result(true, "");
        }

        static Result rejected(String message) {
            return new Result(false, message);
        }
    }

    private Gear gear = Gear.PARK;

    public Gear gear() {
        return gear;
    }

    /**
     * Requests a gear.
     *
     * @param target       wanted gear
     * @param forwardSpeed car speed along its heading, m/s (negative when rolling backwards)
     * @param brake        current brake pedal, 0..1
     */
    public Result request(Gear target, double forwardSpeed, double brake) {
        if (target == gear) {
            return Result.ok();
        }
        if (gear == Gear.PARK && target != Gear.NEUTRAL && brake < BRAKE_TO_SHIFT) {
            return Result.rejected("Press the brake to shift out of Park");
        }
        switch (target) {
            case PARK -> {
                if (Math.abs(forwardSpeed) > PARK_MAX_SPEED) {
                    return Result.rejected("Stop the car before selecting Park");
                }
            }
            case DRIVE -> {
                if (forwardSpeed < -DIRECTION_CHANGE_MAX_SPEED) {
                    return Result.rejected("Slow down before selecting Drive");
                }
            }
            case REVERSE -> {
                if (forwardSpeed > DIRECTION_CHANGE_MAX_SPEED) {
                    return Result.rejected("Slow down before selecting Reverse");
                }
            }
            case NEUTRAL -> {
                // always allowed
            }
        }
        gear = target;
        return Result.ok();
    }

    /** Forces a gear without checks (used when the car is reset). */
    public void force(Gear target) {
        gear = target;
    }
}
