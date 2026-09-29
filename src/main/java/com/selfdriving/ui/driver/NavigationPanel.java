package com.selfdriving.ui.driver;

import java.util.List;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.world.Place;

/**
 * Route and autopilot card: pick a destination, see distance, time and the next direction,
 * hand over to the autopilot (or take back control), stop in an emergency, set the autopilot's
 * maximum speed, and start test scenarios.
 */
final class NavigationPanel {

    /** What the controls do. Implemented by the driver screen. */
    interface Actions {
        void planRoute(Place destination);

        void clearRoute();

        void toggleAutopilot();

        void emergencyStop();

        /** Park in a free space on the left (or cancel parking). */
        void autoPark();

        void changeMaxSpeed(double deltaKmh);

        void setEmergencyBraking(boolean enabled);

        void scenarioPedestrian();

        void scenarioStoppedVehicle();

        void scenarioSlowVehicle();

        void scenarioRoadClosed();

        void clearScenarios();

        /** Off, light, normal, heavy traffic. */
        void cycleTraffic();
    }

    private final VBox root = new VBox(8);
    private final ComboBox<Place> destination = new ComboBox<>();
    private final Label summary = new Label("No route");
    private final Label instruction = new Label("Choose a destination and press Go");
    private final Button autopilot = button("Start autopilot", "Hand driving to the autopilot (E)");
    private final Label maxSpeed = new Label();
    private final ToggleButton aeb = new ToggleButton("AEB");
    private final Button trafficButton = button("Traffic", "Other vehicles on the roads: off, light, normal or heavy (Y)");

    NavigationPanel(List<Place> places, Actions actions) {
        root.getStyleClass().add("card");

        Label title = new Label("NAVIGATION  \u00B7  AUTOPILOT");
        title.getStyleClass().add("card-title");

        destination.getItems().setAll(places);
        destination.setPromptText("Choose destination");
        destination.setMaxWidth(Double.MAX_VALUE);
        destination.getStyleClass().add("destination-box");
        destination.setAccessibleText("Destination");
        HBox.setHgrow(destination, Priority.ALWAYS);
        Button go = button("Go", "Plan the fastest route (A*)");
        go.getStyleClass().add("primary");
        go.setOnAction(e -> {
            if (destination.getValue() != null) {
                actions.planRoute(destination.getValue());
            }
        });
        destination.setOnAction(e -> {
            if (destination.getValue() != null) {
                actions.planRoute(destination.getValue());
            }
            root.getParent().requestFocus();
        });
        Button clear = button("\u2715", "Cancel the route");
        clear.setAccessibleText("Cancel route");
        clear.setOnAction(e -> {
            destination.getSelectionModel().clearSelection();
            actions.clearRoute();
        });
        HBox pick = new HBox(6, destination, go, clear);
        pick.setAlignment(Pos.CENTER_LEFT);

        summary.getStyleClass().add("nav-summary");
        instruction.getStyleClass().add("nav-instruction");
        instruction.setWrapText(true);
        instruction.setMinHeight(Region.USE_PREF_SIZE);

        autopilot.getStyleClass().add("autopilot-button");
        autopilot.setOnAction(e -> actions.toggleAutopilot());
        Button stop = button("Emergency stop", "Brake to a stop now and secure the car (X)");
        stop.getStyleClass().add("danger");
        stop.setOnAction(e -> actions.emergencyStop());
        Button slower = button("\u2212", "Lower maximum autopilot speed");
        slower.setAccessibleText("Lower maximum speed");
        slower.setOnAction(e -> actions.changeMaxSpeed(-10));
        Button faster = button("+", "Raise maximum autopilot speed");
        faster.setAccessibleText("Raise maximum speed");
        faster.setOnAction(e -> actions.changeMaxSpeed(10));
        maxSpeed.getStyleClass().add("metric-value-small");
        maxSpeed.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button park = button("Park", "Park in a free space on the left: a bay or along the kerb (Q)");
        park.setOnAction(e -> actions.autoPark());
        HBox drive = new HBox(6, autopilot, stop, park, spacer, slower, maxSpeed, faster);
        drive.setAlignment(Pos.CENTER_LEFT);

        Label scenariosLabel = new Label("TEST");
        scenariosLabel.getStyleClass().add("dock-group-label");
        Button pedestrian = button("Pedestrian", "A pedestrian steps out ahead");
        pedestrian.setOnAction(e -> actions.scenarioPedestrian());
        Button stopped = button("Stopped car", "A car stands in your lane ahead, then pulls away");
        stopped.setOnAction(e -> actions.scenarioStoppedVehicle());
        Button slow = button("Slow car", "A slow auto-rickshaw ahead: watch the car overtake on wider roads");
        slow.setOnAction(e -> actions.scenarioSlowVehicle());
        Button closed = button("Closed", "Close a road on the route and re-route around it");
        closed.setOnAction(e -> actions.scenarioRoadClosed());
        Button reset = button("Clear", "Remove test objects and re-open all roads");
        reset.setOnAction(e -> actions.clearScenarios());
        style(aeb, "Automatic emergency braking on or off");
        aeb.setOnAction(e -> actions.setEmergencyBraking(aeb.isSelected()));
        trafficButton.setOnAction(e -> actions.cycleTraffic());
        HBox scenarios = new HBox(6, scenariosLabel, pedestrian, stopped, slow, closed, reset);
        scenarios.setAlignment(Pos.CENTER_LEFT);
        Label roadLabel = new Label("ROAD");
        roadLabel.getStyleClass().add("dock-group-label");
        HBox settings = new HBox(6, roadLabel, trafficButton, spacer(), aeb);
        settings.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(title, pick, summary, instruction, drive, scenarios, settings);
    }

    VBox node() {
        return root;
    }

    void update(SimulationSnapshot s) {
        SimulationSnapshot.Navigation nav = s.navigation();
        if (nav == null) {
            summary.setText("No route");
            instruction.setText(s.mode() == DriveMode.MANUAL ? "Choose a destination and press Go" : "");
        } else {
            double minutes = Math.max(1, Math.round(nav.remainingSeconds() / 60));
            summary.setText(String.format("%s  \u00B7  %.1f km  \u00B7  %.0f min", nav.route().destination(),
                    nav.remainingDistance() / 1000, minutes));
            String line = String.format("%s in %s", nav.nextInstruction(), distance(nav.distanceToNext()));
            if (s.autopilot() != null) {
                line += "\n" + s.autopilot().status();
            }
            instruction.setText(line);
        }
        boolean engaged = s.mode() == DriveMode.AUTOPILOT || s.mode() == DriveMode.AUTO_PARK;
        autopilot.setText(engaged ? "Take over" : "Start autopilot");
        setClass(autopilot, "engaged", engaged);
        autopilot.setDisable(nav == null && !engaged);
        maxSpeed.setText(String.format("Max %.0f km/h", s.settings().maxAutopilotSpeed() * 3.6));
        aeb.setSelected(s.settings().emergencyBrakingEnabled());
        trafficButton.setText("Traffic: " + trafficLevel(s.settings().trafficCount()));
    }

    /** Name for a traffic amount. */
    static String trafficLevel(int count) {
        if (count == 0) {
            return "Off";
        }
        return count < 120 ? "Light" : count < 200 ? "Normal" : "Heavy";
    }

    static String distance(double metres) {
        if (metres >= 1000) {
            return String.format("%.1f km", metres / 1000);
        }
        return String.format("%.0f m", Math.round(metres / 10) * 10.0);
    }

    private static Button button(String text, String tooltip) {
        Button b = new Button(text);
        style(b, tooltip);
        return b;
    }

    private static void style(ButtonBase b, String tooltip) {
        b.getStyleClass().add("dock-button");
        b.setTooltip(new Tooltip(tooltip));
        b.setAccessibleHelp(tooltip);
        b.setFocusTraversable(false);
        b.setMinWidth(Region.USE_PREF_SIZE);
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    private static void setClass(javafx.scene.Node node, String styleClass, boolean present) {
        if (present && !node.getStyleClass().contains(styleClass)) {
            node.getStyleClass().add(styleClass);
        } else if (!present) {
            node.getStyleClass().remove(styleClass);
        }
    }
}
