package com.selfdriving.physics;

/**
 * Combined-slip tyre model based on the Pacejka magic formula.
 *
 * <p>The contact patch slides over the road with velocity
 * {@code (vLong - omega * R, vLat)}. Slip is that sliding speed divided by a reference speed
 * (the larger of the wheel's travel speed and its rim speed). This one number covers
 * braking, acceleration, cornering and any mix of them:
 * <ul>
 *   <li>small slips: force grows linearly (the tyre's stiffness),</li>
 *   <li>around {@link Surface#peakSlip()}: maximum grip,</li>
 *   <li>locked wheel or full sideways slide: slip = 1, grip drops to the sliding value.</li>
 * </ul>
 * The force always points against the sliding direction, so a locked wheel pushes back along
 * its direction of travel, and grip used for braking is not available for cornering
 * (the friction circle).
 */
public final class TireModel {

    /**
     * Below this speed the reference speed is held constant, so slip stays finite when the car
     * is almost stopped. The tyre then behaves like a stiff damper, which holds the car still.
     */
    public static final double MIN_REFERENCE_SPEED = 1.0;

    private TireModel() {
    }

    /**
     * Tyre force in the wheel's own frame.
     *
     * @param longitudinal force along the wheel's rolling direction, N (positive = forward)
     * @param lateral      force across the wheel, N (positive = to the left)
     * @param slip         combined slip used for the magic formula
     */
    public record Force(double longitudinal, double lateral, double slip) {
    }

    /**
     * Computes the tyre force.
     *
     * @param vLong   wheel-centre velocity along the wheel's heading, m/s
     * @param vLat    wheel-centre velocity across the wheel (positive = left), m/s
     * @param omega   wheel angular velocity, rad/s (positive = rolling forward)
     * @param radius  wheel radius, m
     * @param load    vertical load, N
     * @param surface road surface
     */
    public static Force compute(double vLong, double vLat, double omega, double radius,
                                double load, Surface surface) {
        if (load <= 0) {
            return new Force(0, 0, 0);
        }
        double rimSpeed = omega * radius;
        double slideLong = vLong - rimSpeed;
        double slideLat = vLat;
        double slideSpeed = Math.hypot(slideLong, slideLat);

        double reference = Math.max(Math.max(Math.hypot(vLong, vLat), Math.abs(rimSpeed)),
                MIN_REFERENCE_SPEED);
        double slip = slideSpeed / reference;
        if (slideSpeed < 1e-9) {
            return new Force(0, 0, 0);
        }
        double force = load * surface.forceCoefficient(slip);
        return new Force(-force * slideLong / slideSpeed, -force * slideLat / slideSpeed, slip);
    }

    /**
     * Longitudinal slip ratio {@code (omega*R - v) / |v|}, as used by ABS and traction control.
     * Negative while braking (wheel slower than the car), positive while spinning.
     */
    public static double slipRatio(double vLong, double omega, double radius) {
        return (omega * radius - vLong) / Math.max(Math.abs(vLong), MIN_REFERENCE_SPEED);
    }

    /** Slip angle in radians: the angle between where the wheel points and where it moves. */
    public static double slipAngle(double vLong, double vLat) {
        return Math.atan2(vLat, Math.max(Math.abs(vLong), MIN_REFERENCE_SPEED));
    }
}
