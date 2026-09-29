package com.selfdriving.ui.driver;

import java.util.EnumMap;
import java.util.Map;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.selfdriving.physics.Gear;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.render.CameraRig;
import com.selfdriving.vehicle.VehicleState;

/**
 * Instrument read-out over the 3D view, like the top of a centre display: speed, drive
 * selector, battery and range on the left; drive mode, ABS/traction lights and the running
 * 0-100 km/h timer on the right.
 */
final class HudOverlay {

    private final BorderPane root = new BorderPane();
    private final Label speed = new Label("0");
    private final Map<Gear, Label> gearLetters = new EnumMap<>(Gear.class);
    private final Label battery = new Label();
    private final Label absLight = new Label("ABS");
    private final Label tcsLight = new Label("TCS");
    private final Label timer = new Label();
    private final Label cameraLabel = new Label();

    /** @param onGearTapped called when the driver taps a letter of the drive selector */
    HudOverlay(java.util.function.Consumer<Gear> onGearTapped) {
        Label unit = new Label("km/h");
        unit.getStyleClass().add("hud-unit");
        speed.getStyleClass().add("hud-speed");
        speed.setAccessibleText("Speed");
        HBox speedRow = new HBox(8, speed, unit);
        speedRow.setAlignment(Pos.BASELINE_LEFT);
        speedRow.setMouseTransparent(true);

        HBox gears = new HBox(2);
        gears.setPickOnBounds(false);
        for (Gear gear : Gear.values()) {
            Label letter = new Label(gear.letter());
            letter.getStyleClass().add("gear-letter");
            letter.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
            letter.setAccessibleText("Select " + gear.name().toLowerCase(java.util.Locale.ROOT));
            letter.setOnMouseClicked(e -> onGearTapped.accept(gear));
            gearLetters.put(gear, letter);
            gears.getChildren().add(letter);
        }
        battery.getStyleClass().add("hud-battery");
        battery.setMouseTransparent(true);

        VBox left = new VBox(2, speedRow, gears, battery);
        left.getStyleClass().add("hud");
        left.setMaxWidth(VBox.USE_PREF_SIZE);
        left.setPickOnBounds(false);

        Label mode = new Label("MANUAL");
        mode.getStyleClass().add("mode-chip");
        absLight.getStyleClass().add("telltale");
        tcsLight.getStyleClass().add("telltale");
        timer.getStyleClass().add("timer-chip");
        timer.setVisible(false);
        timer.setManaged(false);
        HBox right = new HBox(8, timer, absLight, tcsLight, mode);
        right.setAlignment(Pos.TOP_RIGHT);
        right.setPadding(new Insets(22, 22, 0, 0));

        right.setMouseTransparent(true);
        HBox top = new HBox(left, spacer(), right);
        top.setPickOnBounds(false);
        cameraLabel.getStyleClass().add("camera-label");
        cameraLabel.setMouseTransparent(true);

        root.setTop(top);
        root.setBottom(cameraLabel);
        // Only the drive selector letters take clicks; everything else lets them through.
        root.setPickOnBounds(false);
    }

    BorderPane node() {
        return root;
    }

    void update(SimulationSnapshot snapshot, CameraRig.Mode cameraMode) {
        VehicleState s = snapshot.vehicle();
        speed.setText(Long.toString(Math.round(Math.abs(s.forwardSpeed()) * 3.6)));
        for (Map.Entry<Gear, Label> entry : gearLetters.entrySet()) {
            setClass(entry.getValue(), "active", entry.getKey() == s.gear());
        }
        battery.setText(String.format("Battery %.0f %%  \u00B7  %.0f km", s.batteryCharge() * 100, s.rangeKm()));

        telltale(absLight, "ABS", s.absEnabled(), s.absActive());
        telltale(tcsLight, "TCS", s.tractionEnabled(), s.tractionActive());

        boolean timing = snapshot.accelTestRunning();
        timer.setVisible(timing);
        timer.setManaged(timing);
        if (timing) {
            timer.setText(String.format("0\u2013100  %.2f s", snapshot.accelTestTime()));
        }
        String paused = snapshot.paused() ? "   \u00B7   PAUSED" : "";
        String slow = snapshot.timeScale() < 0.99 ? String.format("   \u00B7   %.2f\u00D7 speed", snapshot.timeScale()) : "";
        cameraLabel.setText("View: " + cameraMode.label() + paused + slow);
    }

    private static void telltale(Label light, String name, boolean enabled, boolean active) {
        light.setText(enabled ? name : name + " OFF");
        setClass(light, "off", !enabled);
        setClass(light, "active", enabled && active);
        setClass(light, "on", enabled && !active);
    }

    private static void setClass(Label label, String styleClass, boolean present) {
        if (present && !label.getStyleClass().contains(styleClass)) {
            label.getStyleClass().add(styleClass);
        } else if (!present) {
            label.getStyleClass().remove(styleClass);
        }
    }

    private static javafx.scene.layout.Region spacer() {
        javafx.scene.layout.Region region = new javafx.scene.layout.Region();
        HBox.setHgrow(region, javafx.scene.layout.Priority.ALWAYS);
        return region;
    }
}
