package com.selfdriving.ui.driver;

import java.util.ArrayDeque;
import java.util.Deque;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.ui.Palette;
import com.selfdriving.vehicle.VehicleState;

/**
 * The g-g diagram: the car's acceleration in g, forwards/backwards (vertical) and sideways
 * (horizontal). The dashed circle is the road's grip limit (mu). Tyres can only push the car
 * within that circle, so hard braking in a corner shows up as the dot running along its edge.
 */
final class FrictionCircleView {

    private static final int TRAIL = 180;
    private static final double RANGE_G = 1.2;

    private final Canvas canvas;
    private final Deque<double[]> trail = new ArrayDeque<>();

    FrictionCircleView(double size) {
        canvas = new Canvas(size, size);
        canvas.setAccessibleText("Acceleration diagram");
    }

    Canvas node() {
        return canvas;
    }

    void draw(VehicleState s) {
        double gForward = s.accelForward() / VehicleParams.GRAVITY;
        double gLeft = s.accelLeft() / VehicleParams.GRAVITY;
        trail.addLast(new double[] {gForward, gLeft});
        if (trail.size() > TRAIL) {
            trail.removeFirst();
        }

        double size = canvas.getWidth();
        double cx = size / 2;
        double cy = size / 2;
        double scale = (size / 2 - 14) / RANGE_G;
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, size, size);

        g.setStroke(Palette.BORDER);
        g.setLineWidth(1);
        g.setLineDashes();
        for (double ring : new double[] {0.5, 1.0}) {
            g.strokeOval(cx - ring * scale, cy - ring * scale, 2 * ring * scale, 2 * ring * scale);
        }
        g.strokeLine(cx - RANGE_G * scale, cy, cx + RANGE_G * scale, cy);
        g.strokeLine(cx, cy - RANGE_G * scale, cx, cy + RANGE_G * scale);

        double mu = s.surface().friction();
        g.setStroke(Palette.WARNING);
        g.setLineDashes(4, 4);
        g.strokeOval(cx - mu * scale, cy - mu * scale, 2 * mu * scale, 2 * mu * scale);
        g.setLineDashes();

        g.setFill(Palette.TEXT_MUTED);
        g.setFont(Font.font(10));
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText("accel", cx, 11);
        g.fillText("brake", cx, size - 3);
        g.fillText("0.5 g", cx + 0.5 * scale + 1, cy - 4);
        g.fillText("1 g", cx + 1.0 * scale - 8, cy - 4);

        int i = 0;
        for (double[] p : trail) {
            double alpha = (double) i / trail.size();
            g.setFill(Palette.ACCENT.deriveColor(0, 1, 1, alpha * 0.7));
            double x = cx - p[1] * scale;
            double y = cy - p[0] * scale;
            g.fillOval(x - 2, y - 2, 4, 4);
            i++;
        }
        double x = cx - gLeft * scale;
        double y = cy - gForward * scale;
        g.setFill(Color.WHITE);
        g.fillOval(x - 5, y - 5, 10, 10);

        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(Palette.TEXT);
        g.setFont(Font.font(12));
        g.fillText(String.format("%.2f g", Math.hypot(gForward, gLeft)), 4, size - 3);
        g.setFill(Palette.WARNING);
        g.fillText(String.format("\u03BC %.2f", mu), size - 44, size - 3);
    }
}
