package com.selfdriving.ui.render;

import javafx.scene.PerspectiveCamera;
import javafx.scene.transform.Affine;

import com.selfdriving.vehicle.VehicleState;

/**
 * Follows the car with a choice of views. The camera's heading trails the car's heading
 * slightly, which makes turns and slides easy to read.
 */
public final class CameraRig {

    /** Available views. */
    public enum Mode {
        CHASE("Chase"),
        AUTOPILOT("Autopilot view"),
        TOP("Top down"),
        SIDE("Side");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final double HEADING_LAG = 0.25;

    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Affine transform = new Affine();
    private Mode mode = Mode.CHASE;
    private double smoothedHeading = Double.NaN;

    public CameraRig() {
        camera.setNearClip(0.1);
        camera.setFarClip(3000);
        camera.setFieldOfView(52);
        camera.getTransforms().add(transform);
    }

    public PerspectiveCamera camera() {
        return camera;
    }

    public Mode mode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    /** Switches to the next view and returns it. */
    public Mode cycle() {
        mode = mode.next();
        return mode;
    }

    /**
     * Places the camera for this frame.
     *
     * @param dt frame time, s (for smoothing)
     */
    public void update(VehicleState s, double dt) {
        if (Double.isNaN(smoothedHeading)) {
            smoothedHeading = s.heading();
        }
        double diff = Math.atan2(Math.sin(s.heading() - smoothedHeading), Math.cos(s.heading() - smoothedHeading));
        smoothedHeading += diff * Math.min(1, dt / HEADING_LAG);

        double h = mode == Mode.CHASE || mode == Mode.AUTOPILOT ? smoothedHeading : s.heading();
        double fx = Math.cos(h);
        double fz = Math.sin(h);
        double cx = s.x();
        double cz = s.y();

        switch (mode) {
            case CHASE -> lookAt(cx - fx * 8.5, -2.3, cz - fz * 8.5,
                    cx + fx * 6, -0.8, cz + fz * 6, 0, 1, 0);
            case AUTOPILOT -> lookAt(cx - fx * 13, -7.5, cz - fz * 13,
                    cx + fx * 10, 0, cz + fz * 10, 0, 1, 0);
            case TOP -> lookAt(cx, -70, cz, cx, 0, cz, -fx, 0, -fz);
            case SIDE -> {
                // From the car's right-hand side, slightly ahead, at wheel height.
                double rx = Math.sin(h);
                double rz = -Math.cos(h);
                lookAt(cx + rx * 7 + fx * 1.5, -1.1, cz + rz * 7 + fz * 1.5,
                        cx, -0.6, cz, 0, 1, 0);
            }
        }
    }

    /** Resets smoothing (e.g. after the car is teleported). */
    public void snap() {
        smoothedHeading = Double.NaN;
    }

    /**
     * Points the camera from an eye position at a target. JavaFX cameras look along +Z with +Y
     * pointing down the screen, so the basis is (right, down, forward).
     *
     * @param dx {@code dy} {@code dz} direction that should point down the screen
     */
    private void lookAt(double ex, double ey, double ez, double tx, double ty, double tz,
                        double dx, double dy, double dz) {
        double fx = tx - ex;
        double fy = ty - ey;
        double fz = tz - ez;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl;
        fy /= fl;
        fz /= fl;
        // right = down x forward
        double rx = dy * fz - dz * fy;
        double ry = dz * fx - dx * fz;
        double rz = dx * fy - dy * fx;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        // down = forward x right
        double ux = fy * rz - fz * ry;
        double uy = fz * rx - fx * rz;
        double uz = fx * ry - fy * rx;
        transform.setToTransform(rx, ux, fx, ex, ry, uy, fy, ey, rz, uz, fz, ez);
    }
}
