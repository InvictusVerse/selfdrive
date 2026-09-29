package com.selfdriving.ui.driver;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

import com.selfdriving.navigation.RoadGraph;
import com.selfdriving.navigation.Route;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.Palette;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Place;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Road;
import com.selfdriving.world.World;

/**
 * Top-down map, north up, drawn from the same data as the 3D world (so it works offline).
 * By default it follows the car; click it to switch to an overview of the whole area.
 * Shows buildings, roads, the planned route and destination, closed roads, other road users,
 * the car and its recent path.
 */
final class MapView {

    private static final int TRAIL_POINTS = 900;
    private static final double TRAIL_SPACING = 2.0;
    private static final double FOLLOW_SPAN = 650;
    private static final double PADDING = 24;

    private final World world;
    private final RoadGraph graph;
    private final double[] bounds;
    private final Pane container = new Pane();
    private final Canvas canvas = new Canvas();
    private final Deque<Point2> trail = new ArrayDeque<>();
    private boolean overview;

    private double scale;
    private double originX;
    private double originY;
    private double width;
    private double height;

    MapView(World world, RoadGraph graph) {
        this.world = world;
        this.graph = graph;
        this.bounds = world.bounds();
        canvas.widthProperty().bind(container.widthProperty());
        canvas.heightProperty().bind(container.heightProperty());
        container.getChildren().add(canvas);
        container.setMinSize(0, 0);
        container.setAccessibleText("Map. Click to switch between following the car and the overview.");
        container.setOnMouseClicked(e -> overview = !overview);
        container.setCursor(javafx.scene.Cursor.HAND);
    }

    Pane node() {
        return container;
    }

    void clearTrail() {
        trail.clear();
    }

    void draw(SimulationSnapshot snapshot) {
        VehicleState s = snapshot.vehicle();
        Point2 here = new Point2(s.x(), s.y());
        if (trail.isEmpty() || trail.peekLast().distanceTo(here) > TRAIL_SPACING) {
            trail.addLast(here);
            if (trail.size() > TRAIL_POINTS) {
                trail.removeFirst();
            }
        }

        width = canvas.getWidth();
        height = canvas.getHeight();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(Color.web("#101216"));
        g.fillRect(0, 0, width, height);
        if (width < 10 || height < 10) {
            return;
        }
        setView(s);

        g.setFill(Color.web("#1a2620"));
        for (double[] park : world.city().parks()) {
            fillRect(g, park[0], park[1], park[2], park[3]);
        }
        g.setFill(Color.web("#23272e"));
        for (Obstacle b : world.buildings()) {
            OrientedBox box = b.box();
            fillRect(g, box.cx() - box.halfLength(), box.cy() - box.halfWidth(),
                    box.cx() + box.halfLength(), box.cy() + box.halfWidth());
        }

        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        g.setStroke(Palette.ROAD);
        for (Road road : world.roads()) {
            g.setLineWidth(Math.max(2, road.width() * scale));
            polyline(g, road.centre().points(), road.centre().closed());
        }

        g.setStroke(Palette.DANGER);
        g.setLineWidth(Math.max(3, 3 * scale));
        g.setLineDashes(6, 5);
        for (int id : snapshot.closedEdges()) {
            polyline(g, graph.edge(id).centre().points(), false);
        }
        g.setLineDashes();

        Route route = snapshot.navigation() == null ? null : snapshot.navigation().route();
        if (route != null) {
            g.setStroke(Palette.ACCENT);
            g.setLineWidth(Math.max(3.5, 2.5 * scale));
            g.beginPath();
            int startIndex = 0;
            while (startIndex < route.size() - 1 && route.arc(startIndex) < snapshot.navigation().arc()) {
                startIndex++;
            }
            g.moveTo(sx(s.x()), sy(s.y()));
            for (int i = startIndex; i < route.size(); i += 2) {
                g.lineTo(sx(route.x(i)), sy(route.y(i)));
            }
            g.lineTo(sx(route.x(route.size() - 1)), sy(route.y(route.size() - 1)));
            g.stroke();
            drawPin(g, route.x(route.size() - 1), route.y(route.size() - 1), route.destination());
        }

        if (overview) {
            g.setFill(Palette.TEXT_MUTED);
            g.setFont(Font.font(11));
            g.setTextAlign(TextAlignment.CENTER);
            for (Place place : world.places()) {
                g.fillText(place.name(), sx(place.location().x()), sy(place.location().y()) - 8);
            }
            g.setTextAlign(TextAlignment.LEFT);
        }

        if (trail.size() > 1) {
            g.setStroke(Palette.ACCENT.deriveColor(0, 1, 1, 0.45));
            g.setLineWidth(2);
            polyline(g, List.copyOf(trail), false);
        }

        for (SimulationSnapshot.ActorState a : snapshot.actors()) {
            g.setFill(switch (a.kind()) {
                case PEDESTRIAN -> Palette.WARNING;
                case BARRIER -> Palette.DANGER;
                default -> Palette.TEXT_MUTED;
            });
            double r = a.kind() == Obstacle.Kind.PEDESTRIAN ? 4 : 5;
            g.fillOval(sx(a.box().cx()) - r, sy(a.box().cy()) - r, 2 * r, 2 * r);
        }

        drawCar(g, sx(s.x()), sy(s.y()), s.heading());
        drawScaleBar(g);

        g.setFill(Palette.TEXT_MUTED);
        g.setFont(Font.font(12));
        g.fillText(overview ? "OVERVIEW  \u00B7  click to follow the car" : "MAP  \u00B7  click for overview", 16, 22);
        g.fillText("N \u2191", width - 34, 22);
    }

    private void setView(VehicleState s) {
        if (overview) {
            double worldW = bounds[2] - bounds[0];
            double worldH = bounds[3] - bounds[1];
            scale = Math.min((width - 2 * PADDING) / worldW, (height - 2 * PADDING) / worldH);
            originX = (bounds[0] + bounds[2]) / 2;
            originY = (bounds[1] + bounds[3]) / 2;
        } else {
            scale = width / FOLLOW_SPAN;
            originX = s.x();
            originY = s.y();
        }
    }

    private double sx(double x) {
        return width / 2 + (x - originX) * scale;
    }

    private double sy(double y) {
        return height / 2 - (y - originY) * scale;
    }

    private void fillRect(GraphicsContext g, double x0, double y0, double x1, double y1) {
        g.fillRect(sx(x0), sy(y1), (x1 - x0) * scale, (y1 - y0) * scale);
    }

    private void polyline(GraphicsContext g, List<Point2> points, boolean closed) {
        g.beginPath();
        g.moveTo(sx(points.get(0).x()), sy(points.get(0).y()));
        for (int i = 1; i < points.size(); i++) {
            g.lineTo(sx(points.get(i).x()), sy(points.get(i).y()));
        }
        if (closed) {
            g.closePath();
        }
        g.stroke();
    }

    private void drawPin(GraphicsContext g, double x, double y, String label) {
        double px = sx(x);
        double py = sy(y);
        g.setFill(Palette.ACCENT);
        g.fillOval(px - 7, py - 7, 14, 14);
        g.setFill(Color.WHITE);
        g.fillOval(px - 3, py - 3, 6, 6);
        g.setFont(Font.font(12));
        g.setFill(Palette.TEXT);
        g.fillText(label, px + 10, py + 4);
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

    private void drawScaleBar(GraphicsContext g) {
        double metres = overview ? 200 : 100;
        double length = metres * scale;
        double x = 16;
        double y = height - 16;
        g.setStroke(Palette.TEXT_MUTED);
        g.setLineWidth(2);
        g.strokeLine(x, y, x + length, y);
        g.strokeLine(x, y - 4, x, y);
        g.strokeLine(x + length, y - 4, x + length, y);
        g.setFill(Palette.TEXT_MUTED);
        g.setFont(Font.font(11));
        g.fillText(String.format("%.0f m", metres), x + length + 6, y + 4);
    }
}
