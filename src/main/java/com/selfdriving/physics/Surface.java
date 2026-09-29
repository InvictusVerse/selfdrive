package com.selfdriving.physics;

/**
 * Road surface. Each surface has its own Pacejka "magic formula" coefficients.
 *
 * <p>The coefficients are the widely published simplified values for each road condition:
 * {@code B} stiffness, {@code C} shape, {@code D} peak friction coefficient, {@code E} curvature.
 */
public enum Surface {

    DRY("Dry", 10.0, 1.9, 1.0, 0.97),
    WET("Wet", 12.0, 2.3, 0.82, 1.0),
    SNOW("Snow", 5.0, 2.0, 0.3, 1.0),
    ICE("Ice", 4.0, 2.0, 0.1, 1.0);

    private final String label;
    private final double b;
    private final double c;
    private final double d;
    private final double e;
    private final double peakSlip;

    Surface(String label, double b, double c, double d, double e) {
        this.label = label;
        this.b = b;
        this.c = c;
        this.d = d;
        this.e = e;
        this.peakSlip = findPeakSlip(b, c, e);
    }

    /** Human-readable name for the UI. */
    public String label() {
        return label;
    }

    /** Stiffness factor B. */
    public double b() {
        return b;
    }

    /** Shape factor C. */
    public double c() {
        return c;
    }

    /** Peak friction coefficient D (often written as mu). */
    public double friction() {
        return d;
    }

    /** Curvature factor E. */
    public double e() {
        return e;
    }

    /** Slip at which the tyre produces its maximum force on this surface. */
    public double peakSlip() {
        return peakSlip;
    }

    /**
     * Normalised tyre force for a given slip: the magic formula without the load term.
     * Multiply by vertical load to get newtons. Result is in {@code [0, D]}.
     *
     * @param slip combined slip (dimensionless, sign ignored)
     */
    public double forceCoefficient(double slip) {
        double bx = b * Math.abs(slip);
        return d * Math.sin(c * Math.atan(bx - e * (bx - Math.atan(bx))));
    }

    private static double findPeakSlip(double b, double c, double e) {
        double bestSlip = 0;
        double bestValue = -1;
        for (int i = 1; i <= 2000; i++) {
            double slip = i * 0.0005;
            double bx = b * slip;
            double value = Math.sin(c * Math.atan(bx - e * (bx - Math.atan(bx))));
            if (value > bestValue) {
                bestValue = value;
                bestSlip = slip;
            }
        }
        return bestSlip;
    }
}
