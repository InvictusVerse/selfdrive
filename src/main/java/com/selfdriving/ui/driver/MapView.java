package com.selfdriving.ui.driver;

import java.util.ArrayDeque;
import java.util.Deque;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;

import com.selfdriving.ui.Palette;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.Point2;
import com.selfdriving.world.ProvingGround;
import com.selfdriving.world.Road;

/**
 * Top-down map of the proving ground (north up) with the car and its recent path. Drawn from
 * the same road data as the 3D world, so it works offline.
 */
final class MapView {

    private static final int TRAIL_POINTS = 900;
    private static final double TRAIL_SPACING = 2.0;
    private static final double PADDING = 28;

    private final ProvingGround ground;
    private final double[] bounds;
    private final Pane container = new Pane();
    private final Canvas canvas = new Canvas();
    private final Deque<Point2> trail = new ArrayDeque<>();

    MapView(ProvingGround ground) {
        this.ground = ground;
        this.bounds = ground.roadBounds();
        canvas.widthProperty().bind(container.widthProperty());
        canvas.heightProperty().bind(container.heightProperty());
        container.getChildren().add(canvas);
        container.setMinSize(0, 0);
        container.setAccessibleText("Map of the proving ground");
    }

    Pane node() {
        return container;
    }

    void clearTrail() {
        trail.clear();
    }

    void draw(VehicleState s) {
        Point2 here = new Point2(s.x(), s.y());
        if (trail.isEmpty() || trail.peekLast().distanceTo(here) > TRAIL_SPACING) {
            trail.addLast(here);
            if (trail.size() > TRAIL_POINTS) {
                trail.removeFirst();
            }
        }

        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#101216"));
        g.fillRect(0, 0, w, h);
        if (w < 10 || h < 10) {
            return;
        }

        double worldW = bounds[2] - bounds[0];
        double worldH = bounds[3] - bounds[1];
        double scale = Math.min((w - 2 * PADDING) / worldW, (h - 2 * PADDING) / worldH);
        double offsetX = (w - worldW * scale) / 2 - bounds[0] * scale;
        double offsetY = (h + worldH * scale) / 2 + bounds[1] * scale;

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (Road road : ground.roads()) {
            g.setStroke(Palette.ROAD);
            g.setLineWidth(Math.max(3, road.width() * scale));
            strokePolyline(g, road.centre().points(), road.centre().closed(), scale, offsetX, offsetY);
        }

        // Braking zone
        g.setStroke(Color.web("#c9a227"));
        g.setLineWidth(2);
        double bx = ground.brakeZoneStartX();
        g.strokeLine(offsetX + bx * scale, offsetY, offsetX + (bx + 200) * scale, offsetY);

        // Trail
        if (trail.size() > 1) {
            g.setStroke(Palette.ACCENT.deriveColor(0, 1, 1, 0.8));
            g.setLineWidth(2.5);
            g.beginPath();
            boolean first = true;
            for (Point2 p : trail) {
                double px = offsetX + p.x() * scale;
                double py = offsetY - p.y() * scale;
                if (first) {
                    g.moveTo(px, py);
                    first = false;
                } else {
                    g.lineTo(px, py);
                }
            }
            g.stroke();
        }

        drawCar(g, offsetX + s.x() * scale, offsetY - s.y() * scale, s.heading());
        drawScaleBar(g, scale, h);

        g.setFill(Palette.TEXT_MUTED);
        g.setFont(Font.font(12));
        g.fillText("PROVING GROUND", 16, 22);
        g.fillText("N \u2191", w - 34, 22);
    }

    private static void strokePolyline(GraphicsContext g, java.util.List<Point2> points, boolean closed,
                                       double scale, double ox, double oy) {
        g.beginPath();
        Point2 first = points.get(0);
        g.moveTo(ox + first.x() * scale, oy - first.y() * scale);
        for (int i = 1; i < points.size(); i++) {
            g.lineTo(ox + points.get(i).x() * scale, oy - points.get(i).y() * scale);
        }
        if (closed) {
            g.closePath();
        }
        g.stroke();
    }

    private static void drawCar(GraphicsContext g, double x, double y, double heading) {
        double size = 11;
        double c = Math.cos(heading);
        double s = Math.sin(heading);
        // Screen y points down, so north (world +y) is -y on screen.
        double[] xs = {x + c * size, x - c * size * 0.7 - s * size * 0.65, x - c * size * 0.35,
                x - c * size * 0.7 + s * size * 0.65};
        double[] ys = {y - s * size, y + s * size * 0.7 - c * size * 0.65, y + s * size * 0.35,
                y + s * size * 0.7 + c * size * 0.65};
        g.setFill(Palette.ACCENT.deriveColor(0, 1, 1, 0.25));
        g.fillOval(x - 16, y - 16, 32, 32);
        g.setFill(Palette.TEXT);
        g.fillPolygon(xs, ys, 4);
    }

    private static void drawScaleBar(GraphicsContext g, double scale, double height) {
        double length = 100 * scale;
        double x = 16;
        double y = height - 16;
        g.setStroke(Palette.TEXT_MUTED);
        g.setLineWidth(2);
        g.strokeLine(x, y, x + length, y);
        g.strokeLine(x, y - 4, x, y);
        g.strokeLine(x + length, y - 4, x + length, y);
        g.setFill(Palette.TEXT_MUTED);
        g.setFont(Font.font(11));
        g.fillText("100 m", x + length + 6, y + 4);
    }
}
