package com.selfdriving.ui.driver;

import java.util.EnumMap;
import java.util.Map;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.render.CameraRig;
import com.selfdriving.vehicle.VehicleState;

/**
 * Bottom bar of the driver display: drive selector, road surface, driver aids, view options
 * and simulation controls. Every button also has a keyboard shortcut (shown in its tooltip),
 * and buttons never take keyboard focus, so the driving keys always reach the car.
 */
final class ControlDock {

    /** What the buttons do. Implemented by the driver screen. */
    interface Actions {
        void selectGear(Gear gear);

        void selectSurface(Surface surface);

        void setAbs(boolean enabled);

        void setTractionControl(boolean enabled);

        void cycleCamera();

        void setForces(boolean visible);

        void setSlowMotion(boolean slow);

        void setPaused(boolean paused);

        void resetCar();

        void toggleHelp();
    }

    private static final String[] GEAR_KEYS = {"1", "2", "3", "4"};

    /** Below this dock width the group labels (DRIVE, ROAD...) are hidden to make room. */
    private static final double LABELS_MIN_WIDTH = 1420;

    private final HBox root = new HBox();
    private final Map<Gear, ToggleButton> gearButtons = new EnumMap<>(Gear.class);
    private final Map<Surface, ToggleButton> surfaceButtons = new EnumMap<>(Surface.class);
    private final ToggleButton abs = toggle("ABS", "Anti-lock brakes (B)");
    private final ToggleButton tcs = toggle("TCS", "Traction control (T)");
    private final Button camera = button("View", "Change camera (C)");
    private final ToggleButton forces = toggle("Forces", "Show tyre force arrows (F)");
    private final ToggleButton slow = toggle("Slow-mo", "Quarter-speed simulation (M)");
    private final ToggleButton pause = toggle("Pause", "Pause the simulation (P)");

    ControlDock(Actions actions) {
        root.getStyleClass().add("dock");

        root.getChildren().add(groupLabel("DRIVE"));
        Gear[] gears = Gear.values();
        for (int i = 0; i < gears.length; i++) {
            Gear gear = gears[i];
            ToggleButton b = toggle(gear.letter(), gearName(gear) + " (" + GEAR_KEYS[i] + ")");
            b.getStyleClass().add("gear-button");
            b.setOnAction(e -> actions.selectGear(gear));
            gearButtons.put(gear, b);
            root.getChildren().add(b);
        }
        root.getChildren().addAll(separator(), groupLabel("ROAD"));
        for (Surface surface : Surface.values()) {
            ToggleButton b = toggle(surface.label(), surface.label() + " road, grip \u03BC = "
                    + surface.friction() + " (G cycles)");
            b.setOnAction(e -> actions.selectSurface(surface));
            surfaceButtons.put(surface, b);
            root.getChildren().add(b);
        }
        abs.setOnAction(e -> actions.setAbs(abs.isSelected()));
        tcs.setOnAction(e -> actions.setTractionControl(tcs.isSelected()));
        root.getChildren().addAll(separator(), groupLabel("AIDS"), abs, tcs);

        camera.setOnAction(e -> actions.cycleCamera());
        forces.setOnAction(e -> actions.setForces(forces.isSelected()));
        root.getChildren().addAll(separator(), groupLabel("VIEW"), camera, forces);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        slow.setOnAction(e -> actions.setSlowMotion(slow.isSelected()));
        pause.setOnAction(e -> actions.setPaused(pause.isSelected()));
        Button reset = button("Reset", "Back to the start line (Backspace)");
        reset.setOnAction(e -> actions.resetCar());
        Button help = button("Keys", "Show keyboard controls (H)");
        help.setOnAction(e -> actions.toggleHelp());
        root.getChildren().addAll(spacer, slow, pause, reset, help);
    }

    HBox node() {
        return root;
    }

    /** Mirrors the simulation state in the buttons. */
    void update(SimulationSnapshot snapshot, CameraRig.Mode cameraMode, boolean forcesVisible) {
        VehicleState s = snapshot.vehicle();
        gearButtons.forEach((gear, b) -> b.setSelected(gear == s.gear()));
        surfaceButtons.forEach((surface, b) -> b.setSelected(surface == s.surface()));
        abs.setSelected(s.absEnabled());
        tcs.setSelected(s.tractionEnabled());
        camera.setAccessibleText("Camera: " + cameraMode.label());
        forces.setSelected(forcesVisible);
        slow.setSelected(snapshot.timeScale() < 0.99);
        pause.setSelected(snapshot.paused());
    }

    private static String gearName(Gear gear) {
        return switch (gear) {
            case PARK -> "Park";
            case REVERSE -> "Reverse";
            case NEUTRAL -> "Neutral";
            case DRIVE -> "Drive";
        };
    }

    private static ToggleButton toggle(String text, String tooltip) {
        ToggleButton b = new ToggleButton(text);
        style(b, tooltip);
        return b;
    }

    private static Button button(String text, String tooltip) {
        Button b = new Button(text);
        style(b, tooltip);
        return b;
    }

    private static void style(ButtonBase b, String tooltip) {
        b.getStyleClass().add("dock-button");
        b.setMinWidth(Region.USE_PREF_SIZE);
        b.setTooltip(new Tooltip(tooltip));
        b.setAccessibleHelp(tooltip);
        b.setFocusTraversable(false);
    }

    private Label groupLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dock-group-label");
        label.setMinWidth(Region.USE_PREF_SIZE);
        // The small group labels are the first thing to go on narrow windows.
        label.visibleProperty().bind(root.widthProperty().greaterThan(LABELS_MIN_WIDTH));
        label.managedProperty().bind(label.visibleProperty());
        return label;
    }

    private static Region separator() {
        Region region = new Region();
        region.getStyleClass().add("dock-separator");
        return region;
    }
}
