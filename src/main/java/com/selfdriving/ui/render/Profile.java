package com.selfdriving.ui.render;

/**
 * Smooth curve through control points (monotone cubic Hermite interpolation). Used to shape the
 * car body: height and width as functions of the position along the car. Monotone interpolation
 * never overshoots, so the body has no bumps between control points.
 */
final class Profile {

    private final double[] xs;
    private final double[] ys;
    private final double[] slopes;

    /** @param points pairs of (x, value), x strictly increasing */
    Profile(double... points) {
        if (points.length < 4 || points.length % 2 != 0) {
            throw new IllegalArgumentException("Need at least two (x, value) pairs");
        }
        int n = points.length / 2;
        xs = new double[n];
        ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = points[2 * i];
            ys[i] = points[2 * i + 1];
        }
        slopes = monotoneSlopes(xs, ys);
    }

    double at(double x) {
        int n = xs.length;
        if (x <= xs[0]) {
            return ys[0];
        }
        if (x >= xs[n - 1]) {
            return ys[n - 1];
        }
        int i = 0;
        while (x > xs[i + 1]) {
            i++;
        }
        double h = xs[i + 1] - xs[i];
        double t = (x - xs[i]) / h;
        double t2 = t * t;
        double t3 = t2 * t;
        return (2 * t3 - 3 * t2 + 1) * ys[i]
                + (t3 - 2 * t2 + t) * h * slopes[i]
                + (-2 * t3 + 3 * t2) * ys[i + 1]
                + (t3 - t2) * h * slopes[i + 1];
    }

    double minX() {
        return xs[0];
    }

    double maxX() {
        return xs[xs.length - 1];
    }

    /** Fritsch-Carlson slopes. */
    private static double[] monotoneSlopes(double[] x, double[] y) {
        int n = x.length;
        double[] delta = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            delta[i] = (y[i + 1] - y[i]) / (x[i + 1] - x[i]);
        }
        double[] m = new double[n];
        m[0] = delta[0];
        m[n - 1] = delta[n - 2];
        for (int i = 1; i < n - 1; i++) {
            m[i] = delta[i - 1] * delta[i] <= 0 ? 0 : (delta[i - 1] + delta[i]) / 2;
        }
        for (int i = 0; i < n - 1; i++) {
            if (delta[i] == 0) {
                m[i] = m[i + 1] = 0;
                continue;
            }
            double a = m[i] / delta[i];
            double b = m[i + 1] / delta[i];
            double s = a * a + b * b;
            if (s > 9) {
                double tau = 3 / Math.sqrt(s);
                m[i] = tau * a * delta[i];
                m[i + 1] = tau * b * delta[i];
            }
        }
        return m;
    }
}
