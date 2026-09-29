package com.selfdriving.ui.driver;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.selfdriving.alerts.Alert;
import com.selfdriving.physics.Gear;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.render.CameraRig;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.vehicle.VehicleState;

/**
 * Read-out over the 3D view, like the top of an EV centre display:
 * <ul>
 *   <li>left: speed, speed-limit sign, drive selector (tappable), battery and range;</li>
 *   <li>centre: the next direction, and a red or amber banner for emergency braking or a
 *       collision warning;</li>
 *   <li>right: drive mode, ABS/TCS lights, 0-100 timer, autopilot status and critical alerts,
 *       each with a button to acknowledge it.</li>
 * </ul>
 */
final class HudOverlay {

    private final BorderPane root = new BorderPane();
    private final Label speed = new Label("0");
    private final Label limit = new Label();
    private final Map<Gear, Label> gearLetters = new EnumMap<>(Gear.class);
    private final Label battery = new Label();
    private final Label mode = new Label("MANUAL");
    private final Label absLight = new Label("ABS");
    private final Label tcsLight = new Label("TCS");
    private final Label timer = new Label();
    private final Label autopilotStatus = new Label();
    private final VBox criticalAlerts = new VBox(6);
    private final Label turn = new Label();
    private final Label safetyBanner = new Label();
    private final Label cameraLabel = new Label();
    private final LongConsumer onAcknowledge;
    private List<Alert> shownAlerts = List.of();

    /**
     * @param onGearTapped  called when the driver taps a letter of the drive selector
     * @param onAcknowledge called with an alert id when the driver dismisses a critical alert
     */
    HudOverlay(Consumer<Gear> onGearTapped, LongConsumer onAcknowledge) {
        this.onAcknowledge = onAcknowledge;

        Label unit = new Label("km/h");
        unit.getStyleClass().add("hud-unit");
        speed.getStyleClass().add("hud-speed");
        speed.setAccessibleText("Speed");
        limit.getStyleClass().add("limit-sign");
        limit.setAccessibleText("Speed limit");
        limit.setVisible(false);
        HBox speedRow = new HBox(8, speed, unit, limit);
        speedRow.setAlignment(Pos.CENTER_LEFT);
        speedRow.setMouseTransparent(true);

        HBox gears = new HBox(2);
        gears.setPickOnBounds(false);
        for (Gear gear : Gear.values()) {
            Label letter = new Label(gear.letter());
            letter.getStyleClass().add("gear-letter");
            letter.setAccessibleRole(AccessibleRole.BUTTON);
            letter.setAccessibleText("Select " + gear.name().toLowerCase(Locale.ROOT));
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

        mode.getStyleClass().add("mode-chip");
        absLight.getStyleClass().add("telltale");
        tcsLight.getStyleClass().add("telltale");
        timer.getStyleClass().add("timer-chip");
        timer.setVisible(false);
        timer.setManaged(false);
        HBox chips = new HBox(8, timer, absLight, tcsLight, mode);
        chips.setAlignment(Pos.TOP_RIGHT);
        chips.setMouseTransparent(true);
        autopilotStatus.getStyleClass().add("autopilot-status");
        autopilotStatus.setMouseTransparent(true);
        criticalAlerts.setAlignment(Pos.TOP_RIGHT);
        criticalAlerts.setPickOnBounds(false);
        VBox right = new VBox(8, chips, autopilotStatus, criticalAlerts);
        right.setAlignment(Pos.TOP_RIGHT);
        right.setPadding(new Insets(22, 22, 0, 0));
        right.setPickOnBounds(false);
        right.setMaxWidth(360);

        turn.getStyleClass().add("turn-banner");
        turn.setVisible(false);
        safetyBanner.getStyleClass().add("safety-banner");
        safetyBanner.setVisible(false);
        VBox centre = new VBox(8, turn, safetyBanner);
        centre.setAlignment(Pos.TOP_CENTER);
        centre.setPadding(new Insets(20, 0, 0, 0));
        centre.setMouseTransparent(true);

        Region spacerLeft = new Region();
        Region spacerRight = new Region();
        HBox.setHgrow(spacerLeft, Priority.ALWAYS);
        HBox.setHgrow(spacerRight, Priority.ALWAYS);
        HBox top = new HBox(left, spacerLeft, centre, spacerRight, right);
        top.setPickOnBounds(false);
        cameraLabel.getStyleClass().add("camera-label");
        cameraLabel.setMouseTransparent(true);

        root.setTop(top);
        root.setBottom(cameraLabel);
        // Only the drive selector letters and alert buttons take clicks.
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

        SimulationSnapshot.Navigation nav = snapshot.navigation();
        limit.setVisible(nav != null);
        if (nav != null) {
            limit.setText(Long.toString(Math.round(nav.speedLimit() * 3.6)));
            turn.setText(arrow(nav.nextInstruction()) + "  " + nav.nextInstruction() + "   "
                    + NavigationPanel.distance(nav.distanceToNext()));
        }
        turn.setVisible(nav != null);

        mode.setText(snapshot.mode().label());
        setClass(mode, "autopilot", snapshot.mode() == DriveMode.AUTOPILOT);
        setClass(mode, "emergency", snapshot.mode() == DriveMode.EMERGENCY_STOP);
        if (snapshot.autopilot() != null) {
            autopilotStatus.setText(String.format("%s  \u00B7  %.0f km/h", snapshot.autopilot().status(),
                    snapshot.autopilot().targetSpeed() * 3.6));
        }
        autopilotStatus.setVisible(snapshot.autopilot() != null);

        if (snapshot.safety().emergencyBraking()) {
            safetyBanner.setText("EMERGENCY BRAKING");
            setClass(safetyBanner, "critical", true);
            safetyBanner.setVisible(true);
        } else if (snapshot.safety().warning()) {
            safetyBanner.setText(String.format("COLLISION WARNING  \u00B7  %.1f s", snapshot.safety().timeToCollision()));
            setClass(safetyBanner, "critical", false);
            safetyBanner.setVisible(true);
        } else {
            safetyBanner.setVisible(false);
        }

        telltale(absLight, "ABS", s.absEnabled(), s.absActive());
        telltale(tcsLight, "TCS", s.tractionEnabled(), s.tractionActive());

        boolean timing = snapshot.accelTestRunning();
        timer.setVisible(timing);
        timer.setManaged(timing);
        if (timing) {
            timer.setText(String.format("0\u2013100  %.2f s", snapshot.accelTestTime()));
        }
        updateAlerts(snapshot.criticalAlerts());

        String paused = snapshot.paused() ? "   \u00B7   PAUSED" : "";
        String slow = snapshot.timeScale() < 0.99 ? String.format("   \u00B7   %.2f\u00D7 speed", snapshot.timeScale()) : "";
        cameraLabel.setText("View: " + cameraMode.label() + paused + slow);
    }

    private void updateAlerts(List<Alert> alerts) {
        List<Alert> top = alerts.subList(0, Math.min(3, alerts.size()));
        if (top.equals(shownAlerts)) {
            return;
        }
        shownAlerts = List.copyOf(top);
        criticalAlerts.getChildren().clear();
        for (Alert alert : top) {
            Label text = new Label(alert.message());
            text.setWrapText(true);
            text.getStyleClass().add("alert-text");
            Label dismiss = new Label("\u2715");
            dismiss.getStyleClass().add("alert-dismiss");
            dismiss.setAccessibleRole(AccessibleRole.BUTTON);
            dismiss.setAccessibleText("Acknowledge alert");
            dismiss.setOnMouseClicked(e -> onAcknowledge.accept(alert.id()));
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox card = new HBox(10, text, dismiss);
            card.getStyleClass().add("alert-card");
            card.setAlignment(Pos.CENTER_LEFT);
            card.setMaxWidth(340);
            criticalAlerts.getChildren().add(card);
        }
    }

    private static String arrow(String instruction) {
        if (instruction.startsWith("Turn left")) {
            return "\u2190";
        }
        if (instruction.startsWith("Turn right")) {
            return "\u2192";
        }
        return "\u25CF";
    }

    private static void telltale(Label light, String name, boolean enabled, boolean active) {
        light.setText(enabled ? name : name + " OFF");
        setClass(light, "off", !enabled);
        setClass(light, "active", enabled && active);
        setClass(light, "on", enabled && !active);
    }

    private static void setClass(javafx.scene.Node node, String styleClass, boolean present) {
        if (present && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        } else if (!present) {
            node.getStyleClass().remove(styleClass);
        }
    }
}
