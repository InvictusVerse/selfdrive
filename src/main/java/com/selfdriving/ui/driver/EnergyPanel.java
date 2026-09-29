package com.selfdriving.ui.driver;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import com.selfdriving.simulation.PerformanceMonitor;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.Palette;
import com.selfdriving.vehicle.VehicleState;

/**
 * Powertrain and road-test read-out: motor power (with regeneration), battery, consumption,
 * range, and the latest braking and 0-100 km/h results.
 */
final class EnergyPanel {

    private static final double MAX_DRIVE_KW = 300;
    private static final double MAX_REGEN_KW = 80;

    private final VBox root = new VBox(8);
    private final Canvas powerBar;
    private final Label power = value();
    private final Label motor = value();
    private final Label battery = value();
    private final Label range = value();
    private final Label consumption = value();
    private final Label trip = value();
    private final Label brakeTest = small();
    private final Label accelTest = small();

    EnergyPanel(double width) {
        powerBar = new Canvas(width, 8);
        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(2);
        addMetric(grid, 0, 0, "Power", power);
        addMetric(grid, 1, 0, "Motor", motor);
        addMetric(grid, 0, 2, "Battery", battery);
        addMetric(grid, 1, 2, "Range", range);
        addMetric(grid, 0, 4, "Average", consumption);
        addMetric(grid, 1, 4, "Trip", trip);

        Label tests = new Label("ROAD TESTS");
        tests.getStyleClass().add("card-title");
        brakeTest.setWrapText(true);
        accelTest.setWrapText(true);
        root.getChildren().addAll(powerBar, grid, tests, brakeTest, accelTest);
        root.setPrefWidth(width);
        root.setMaxWidth(width);
    }

    VBox node() {
        return root;
    }

    void update(SimulationSnapshot snapshot) {
        VehicleState s = snapshot.vehicle();
        drawPowerBar(s.motorPowerKw());
        power.setText(String.format("%+d kW", Math.round(s.motorPowerKw())));
        motor.setText(String.format("%,.0f rpm", s.motorRpm()));
        battery.setText(String.format("%.1f %%", s.batteryCharge() * 100));
        range.setText(String.format("%.0f km", s.rangeKm()));
        consumption.setText(String.format("%.0f Wh/km", s.consumptionWhPerKm()));
        trip.setText(String.format("%.2f km", s.tripDistance() / 1000));

        PerformanceMonitor.BrakeTest b = snapshot.lastBrakeTest();
        brakeTest.setText(b == null
                ? (snapshot.brakeTestRunning() ? "Braking: measuring\u2026" : "Braking: press Space at speed")
                : String.format("Braking %.0f\u21920 km/h%n%.1f m  (theory %.1f m)",
                        b.startSpeed() * 3.6, b.distance(), b.theoreticalDistance()));
        PerformanceMonitor.AccelerationTest a = snapshot.lastAccelTest();
        accelTest.setText(a == null
                ? "0\u2013100: full throttle from a stop"
                : String.format("0\u2013100 km/h: %.2f s", a.seconds()));
    }

    /** Centre line = 0 kW; right = drawing power, left (green) = regenerating. */
    private void drawPowerBar(double kw) {
        double w = powerBar.getWidth();
        double h = powerBar.getHeight();
        double zero = w * MAX_REGEN_KW / (MAX_REGEN_KW + MAX_DRIVE_KW);
        GraphicsContext g = powerBar.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        g.setFill(Palette.SURFACE_RAISED);
        g.fillRoundRect(0, 0, w, h, h, h);
        if (kw >= 0) {
            double len = Math.min(1, kw / MAX_DRIVE_KW) * (w - zero);
            g.setFill(Palette.TEXT);
            g.fillRoundRect(zero, 0, len, h, h, h);
        } else {
            double len = Math.min(1, -kw / MAX_REGEN_KW) * zero;
            g.setFill(Palette.SUCCESS);
            g.fillRoundRect(zero - len, 0, len, h, h, h);
        }
        g.setFill(Palette.TEXT_MUTED);
        g.fillRect(zero - 1, 0, 2, h);
    }

    private static void addMetric(GridPane grid, int column, int row, String name, Label valueLabel) {
        Label label = new Label(name);
        label.getStyleClass().add("metric-name");
        grid.add(label, column, row);
        grid.add(valueLabel, column, row + 1);
    }

    private static Label value() {
        Label label = new Label();
        label.getStyleClass().add("metric-value");
        return label;
    }

    private static Label small() {
        Label label = new Label();
        label.getStyleClass().add("metric-value-small");
        return label;
    }
}
