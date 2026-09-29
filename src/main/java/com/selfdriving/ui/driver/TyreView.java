package com.selfdriving.ui.driver;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import com.selfdriving.physics.VehicleModel;
import com.selfdriving.ui.Palette;
import com.selfdriving.vehicle.VehicleState;

/**
 * The four tyres seen from above (front at the top). Each tyre's colour shows how much of its
 * grip is in use, the arrow shows the force it is putting on the road, and the numbers are its
 * load (kN) and slip. "ABS" marks a wheel whose brake is being modulated.
 */
final class TyreView {

    private static final double FORCE_SCALE = 1.0 / 150;

    private final Canvas canvas;

    TyreView(double width, double height) {
        canvas = new Canvas(width, height);
        canvas.setAccessibleText("Tyre forces");
    }

    Canvas node() {
        return canvas;
    }

    void draw(VehicleState s) {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        double cx = w / 2;
        double cy = h / 2 + 4;
        double halfTrack = 36;
        double halfBase = 50;

        g.setStroke(Palette.BORDER);
        g.setLineWidth(1.5);
        g.strokeRoundRect(cx - 26, cy - 72, 52, 144, 22, 22);

        double[][] positions = {
                {cx - halfTrack, cy - halfBase}, {cx + halfTrack, cy - halfBase},
                {cx - halfTrack, cy + halfBase}, {cx + halfTrack, cy + halfBase}};
        for (int i = 0; i < 4; i++) {
            VehicleState.WheelState wheel = s.wheels().get(i);
            drawTyre(g, positions[i][0], positions[i][1], wheel, i == VehicleModel.FL || i == VehicleModel.RL);
        }
    }

    private static void drawTyre(GraphicsContext g, double x, double y, VehicleState.WheelState wheel,
                                 boolean leftSide) {
        g.save();
        g.translate(x, y);
        g.save();
        g.rotate(-Math.toDegrees(wheel.steerAngle()));
        g.setFill(Palette.gripColour(wheel.gripUsage()));
        g.fillRoundRect(-7, -16, 14, 32, 5, 5);
        g.restore();

        // Force arrow: car forward is screen up, car left is screen left.
        double fx = -wheel.forceY() * FORCE_SCALE;
        double fy = -wheel.forceX() * FORCE_SCALE;
        if (Math.hypot(fx, fy) > 2) {
            g.setStroke(Palette.FORCE);
            g.setLineWidth(2.5);
            g.strokeLine(0, 0, fx, fy);
            double angle = Math.atan2(fy, fx);
            double head = 6;
            g.strokeLine(fx, fy, fx - head * Math.cos(angle - 0.5), fy - head * Math.sin(angle - 0.5));
            g.strokeLine(fx, fy, fx - head * Math.cos(angle + 0.5), fy - head * Math.sin(angle + 0.5));
        }

        g.setFont(Font.font(10));
        g.setFill(Palette.TEXT_MUTED);
        g.setTextAlign(leftSide ? TextAlignment.RIGHT : TextAlignment.LEFT);
        double tx = leftSide ? -13 : 13;
        g.fillText(String.format("%.1f kN", wheel.load() / 1000), tx, -4);
        long slip = Math.round(wheel.slipRatio() * 100);
        g.fillText(slip == 0 ? "0 %" : String.format("%+d %%", slip), tx, 9);
        if (wheel.absActive()) {
            g.setFill(Palette.WARNING);
            g.fillText("ABS", tx, 22);
        }
        g.restore();
    }
}
