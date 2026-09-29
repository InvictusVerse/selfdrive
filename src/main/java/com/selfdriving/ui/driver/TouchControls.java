package com.selfdriving.ui.driver;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TouchEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;

import com.selfdriving.ui.Palette;
import com.selfdriving.vehicle.DriverInput;
import com.selfdriving.vehicle.VehicleState;

/**
 * On-screen driving controls for a touchscreen or a mouse: a steering wheel on the left and
 * brake and accelerator pedals on the right, over the 3D view.
 *
 * <ul>
 *   <li><b>Steering wheel:</b> press and drag left or right; let go and it centres itself.</li>
 *   <li><b>Pedals:</b> press and hold; the bar inside shows the actual pedal position.</li>
 * </ul>
 * Each control tracks its own touch point, so on a multi-touch screen one finger can steer
 * while another holds a pedal. Mouse events that the system generates from touches are ignored
 * to avoid handling the same press twice.
 */
final class TouchControls {

    private static final double WHEEL_SIZE = 150;
    private static final double DRAG_FOR_FULL_LOCK = 110;
    private static final double WHEEL_ROTATION_AT_FULL_LOCK = 200;

    private final DriverInput input;
    private final BorderPane root = new BorderPane();
    private final Canvas wheel = new Canvas(WHEEL_SIZE, WHEEL_SIZE);
    private final Pedal brakePedal;
    private final Pedal acceleratorPedal;

    private int wheelTouchId = -1;
    private double dragStartX;
    private double dragStartSteer;
    private double displayedSteer;

    TouchControls(DriverInput input) {
        this.input = input;

        wheel.setAccessibleRole(AccessibleRole.SLIDER);
        wheel.setAccessibleText("Steering wheel: drag left or right");
        wheel.setCursor(javafx.scene.Cursor.H_RESIZE);
        installWheelHandlers();

        brakePedal = new Pedal("BRAKE", 104, 76, input::setTouchBrake);
        acceleratorPedal = new Pedal("ACCEL", 70, 118, input::setTouchAccelerate);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox pedals = new HBox(12, brakePedal.node, acceleratorPedal.node);
        pedals.setAlignment(Pos.BOTTOM_RIGHT);
        pedals.setPickOnBounds(false);

        HBox bar = new HBox(wheel, spacer, pedals);
        bar.setAlignment(Pos.BOTTOM_LEFT);
        bar.setPadding(new Insets(0, 22, 40, 22));
        bar.setPickOnBounds(false);

        root.setBottom(bar);
        root.setPickOnBounds(false);
    }

    BorderPane node() {
        return root;
    }

    boolean isVisible() {
        return root.isVisible();
    }

    void setVisible(boolean visible) {
        root.setVisible(visible);
        if (!visible) {
            input.setTouchSteer(Double.NaN);
            input.setTouchBrake(false);
            input.setTouchAccelerate(false);
        }
    }

    /** Shows the real pedal and steering positions (from keyboard or screen). */
    void update(VehicleState s) {
        if (!root.isVisible()) {
            return;
        }
        displayedSteer = s.steerInput();
        drawWheel(wheelTouchId >= 0 || input.isTouchSteering());
        brakePedal.show(s.brake());
        acceleratorPedal.show(s.throttle());
    }

    // ---- Steering wheel ----------------------------------------------------------------

    private void installWheelHandlers() {
        wheel.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (!e.isSynthesized()) {
                grabWheel(e.getX());
            }
        });
        wheel.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> {
            if (!e.isSynthesized()) {
                dragWheel(e.getX());
            }
        });
        wheel.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
            if (!e.isSynthesized()) {
                releaseWheel();
            }
        });
        wheel.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
            if (wheelTouchId < 0) {
                wheelTouchId = e.getTouchPoint().getId();
                grabWheel(e.getTouchPoint().getX());
            }
            e.consume();
        });
        wheel.addEventHandler(TouchEvent.TOUCH_MOVED, e -> {
            if (e.getTouchPoint().getId() == wheelTouchId) {
                dragWheel(e.getTouchPoint().getX());
            }
            e.consume();
        });
        wheel.addEventHandler(TouchEvent.TOUCH_RELEASED, e -> {
            if (e.getTouchPoint().getId() == wheelTouchId) {
                wheelTouchId = -1;
                releaseWheel();
            }
            e.consume();
        });
    }

    private void grabWheel(double x) {
        dragStartX = x;
        dragStartSteer = displayedSteer;
        input.setTouchSteer(dragStartSteer);
    }

    private void dragWheel(double x) {
        // Dragging to the right turns right (negative steer).
        input.setTouchSteer(dragStartSteer - (x - dragStartX) / DRAG_FOR_FULL_LOCK);
    }

    private void releaseWheel() {
        input.setTouchSteer(Double.NaN);
    }

    private void drawWheel(boolean held) {
        double size = WHEEL_SIZE;
        double c = size / 2;
        double r = size / 2 - 10;
        GraphicsContext g = wheel.getGraphicsContext2D();
        g.clearRect(0, 0, size, size);
        g.setFill(Color.rgb(21, 23, 27, 0.72));
        g.fillOval(4, 4, size - 8, size - 8);

        g.save();
        g.translate(c, c);
        g.rotate(-displayedSteer * WHEEL_ROTATION_AT_FULL_LOCK);
        g.setLineCap(StrokeLineCap.ROUND);
        g.setStroke(held ? Palette.ACCENT : Palette.TEXT_MUTED);
        g.setLineWidth(9);
        g.strokeOval(-r, -r, 2 * r, 2 * r);
        g.setLineWidth(7);
        g.strokeLine(-r, 6, -14, 6);
        g.strokeLine(r, 6, 14, 6);
        g.strokeLine(0, 18, 0, r);
        g.setFill(held ? Palette.ACCENT : Palette.TEXT_MUTED);
        g.fillOval(-16, -12, 32, 32);
        g.setStroke(Palette.TEXT);
        g.setLineWidth(5);
        g.strokeLine(0, -r - 4, 0, -r + 8);
        g.restore();
    }

    // ---- Pedals ------------------------------------------------------------------------

    /** A press-and-hold pedal with a bar showing how far it is pressed. */
    private static final class Pedal {

        private final StackPane node = new StackPane();
        private final Region fill = new Region();
        private final double height;
        private final java.util.function.Consumer<Boolean> action;
        private int touchId = -1;

        Pedal(String name, double width, double height, java.util.function.Consumer<Boolean> action) {
            this.height = height;
            this.action = action;
            node.getStyleClass().add("pedal");
            node.setPrefSize(width, height);
            node.setMaxSize(width, height);
            node.setMinSize(width, height);
            node.setAccessibleRole(AccessibleRole.BUTTON);
            node.setAccessibleText(name + " pedal: press and hold");
            node.setCursor(javafx.scene.Cursor.HAND);

            fill.getStyleClass().add("pedal-fill");
            fill.setMaxHeight(0);
            fill.setMouseTransparent(true);
            StackPane.setAlignment(fill, Pos.BOTTOM_CENTER);

            Label label = new Label(name);
            label.getStyleClass().add("pedal-label");
            label.setMouseTransparent(true);
            VBox lines = new VBox(label);
            lines.setAlignment(Pos.CENTER);
            lines.setMouseTransparent(true);
            node.getChildren().addAll(fill, lines);

            node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
                if (!e.isSynthesized()) {
                    press(true);
                }
            });
            node.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> {
                if (!e.isSynthesized()) {
                    press(false);
                }
            });
            node.addEventHandler(TouchEvent.TOUCH_PRESSED, e -> {
                if (touchId < 0) {
                    touchId = e.getTouchPoint().getId();
                    press(true);
                }
                e.consume();
            });
            node.addEventHandler(TouchEvent.TOUCH_RELEASED, e -> {
                if (e.getTouchPoint().getId() == touchId) {
                    touchId = -1;
                    press(false);
                }
                e.consume();
            });
        }

        private void press(boolean pressed) {
            action.accept(pressed);
            if (pressed) {
                if (!node.getStyleClass().contains("pressed")) {
                    node.getStyleClass().add("pressed");
                }
            } else {
                node.getStyleClass().remove("pressed");
            }
        }

        void show(double position) {
            fill.setMaxHeight(Math.max(0, Math.min(1, position)) * height);
            fill.setPrefHeight(fill.getMaxHeight());
        }
    }
}
