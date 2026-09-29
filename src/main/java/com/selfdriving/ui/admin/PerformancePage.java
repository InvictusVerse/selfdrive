package com.selfdriving.ui.admin;

import java.util.List;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.selfdriving.persistence.TripRepository.Trip;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.simulation.PerformanceMonitor;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * Performance: live graphs of speed and battery power for the last two minutes (sampled twice a
 * second from the moment the page is first opened), energy use per trip, and the latest road
 * tests.
 */
public final class PerformancePage implements Page {

    private static final int SAMPLES = 240;
    private static final double SAMPLE_SECONDS = 0.5;

    private final ApplicationContext context;
    private final Session session;
    private final XYChart.Series<Number, Number> speed = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> target = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> power = new XYChart.Series<>();
    private final XYChart.Series<String, Number> consumption = new XYChart.Series<>();
    private final NumberAxis speedTime = timeAxis();
    private final NumberAxis powerTime = timeAxis();
    private final Label accel = new Label("-");
    private final Label braking = new Label("-");
    private final Label battery = new Label("-");
    private final Label average = new Label("-");
    private final Label error = Ui.errorLabel();
    private final Timeline sampler = new Timeline(new KeyFrame(Duration.seconds(SAMPLE_SECONDS), e -> sample()));
    private final VBox root;
    private double t;

    public PerformancePage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        speed.setName("Speed");
        target.setName("Autopilot target");
        power.setName("Battery power (negative = charging)");
        consumption.setName("Wh/km");

        LineChart<Number, Number> speedChart = new LineChart<>(speedTime, axis("km/h"));
        speedChart.getData().add(speed);
        speedChart.getData().add(target);
        LineChart<Number, Number> powerChart = new LineChart<>(powerTime, axis("kW"));
        powerChart.getData().add(power);
        BarChart<String, Number> tripChart = new BarChart<>(new CategoryAxis(), axis("Wh/km"));
        tripChart.getData().add(consumption);
        tripChart.setLegendVisible(false);
        for (var chart : List.of(speedChart, powerChart)) {
            chart.setCreateSymbols(false);
            chart.setAnimated(false);
        }
        tripChart.setAnimated(false);
        speedChart.setAccessibleText("Speed over the last two minutes");
        powerChart.setAccessibleText("Battery power over the last two minutes");
        tripChart.setAccessibleText("Energy use of recent trips");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        VBox speedCard = Ui.card("SPEED, LAST 2 MINUTES", speedChart);
        VBox powerCard = Ui.card("BATTERY POWER, LAST 2 MINUTES", powerChart);
        VBox tripCard = Ui.card("ENERGY PER TRIP (LATEST 15)", tripChart);
        VBox tests = Ui.card("ROAD TESTS AND BATTERY", Ui.stat(accel, "last 0-100 km/h"),
                Ui.stat(braking, "last full stop, measured / ideal"), Ui.stat(battery, "battery charge and range"),
                Ui.stat(average, "average consumption of all trips shown"));
        for (VBox card : List.of(speedCard, powerCard, tripCard)) {
            VBox.setVgrow(card.getChildren().get(1), Priority.ALWAYS);
            card.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
        tests.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        grid.add(speedCard, 0, 0);
        grid.add(powerCard, 1, 0);
        grid.add(tripCard, 0, 1);
        grid.add(tests, 1, 1);
        for (int i = 0; i < 2; i++) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(50);
            grid.getColumnConstraints().add(c);
            javafx.scene.layout.RowConstraints r = new javafx.scene.layout.RowConstraints();
            r.setPercentHeight(50);
            grid.getRowConstraints().add(r);
        }
        root = Ui.page(Ui.header("Performance", "Live driving data and energy use"), new VBox(8, error, grid));
        VBox.setVgrow(grid, Priority.ALWAYS);

        sampler.setCycleCount(Timeline.INDEFINITE);
        sampler.play();
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        Ui.background(() -> context.history().trips(session, 60), this::showTrips, error::setText);
    }

    @Override
    public void dispose() {
        sampler.stop();
    }

    private void sample() {
        SimulationSnapshot s = context.simulation().latest();
        t += SAMPLE_SECONDS;
        add(speed, t, s.vehicle().speedKmh());
        add(target, t, s.autopilot() == null ? Double.NaN : s.autopilot().targetSpeed() * 3.6);
        add(power, t, s.vehicle().batteryPowerKw());
        for (NumberAxis axis : List.of(speedTime, powerTime)) {
            axis.setLowerBound(Math.max(0, t - SAMPLES * SAMPLE_SECONDS));
            axis.setUpperBound(Math.max(SAMPLES * SAMPLE_SECONDS, t));
        }
        PerformanceMonitor.AccelerationTest a = s.lastAccelTest();
        accel.setText(a == null ? "-" : String.format("%.2f s", a.seconds()));
        PerformanceMonitor.BrakeTest b = s.lastBrakeTest();
        braking.setText(b == null ? "-" : String.format("%.1f m / %.1f m from %.0f km/h", b.distance(),
                b.theoreticalDistance(), b.startSpeed() * 3.6));
        battery.setText(String.format("%.0f %%, %.0f km", s.vehicle().batteryCharge() * 100, s.vehicle().rangeKm()));
    }

    private static void add(XYChart.Series<Number, Number> series, double x, double y) {
        if (Double.isNaN(y)) {
            // A gap: JavaFX cannot draw one, so the target line simply stops being extended.
            return;
        }
        series.getData().add(new XYChart.Data<>(x, y));
        while (series.getData().size() > SAMPLES) {
            series.getData().remove(0);
        }
    }

    private void showTrips(List<Trip> trips) {
        consumption.getData().clear();
        List<Trip> finished = trips.stream().filter(x -> x.drivenM() != null && x.drivenM() > 200).limit(15).toList();
        double km = 0;
        double kwh = 0;
        for (int i = finished.size() - 1; i >= 0; i--) {
            Trip x = finished.get(i);
            double whKm = x.energyKwh() * 1000 / (x.drivenM() / 1000);
            consumption.getData().add(new XYChart.Data<>("#" + x.id(), whKm));
            km += x.drivenM() / 1000;
            kwh += x.energyKwh();
        }
        average.setText(km > 0 ? String.format("%.0f Wh/km over %.1f km", kwh * 1000 / km, km) : "No trips yet");
    }

    private static NumberAxis timeAxis() {
        NumberAxis axis = new NumberAxis(0, SAMPLES * SAMPLE_SECONDS, 30);
        axis.setAutoRanging(false);
        axis.setLabel("s");
        axis.setTickLabelsVisible(false);
        return axis;
    }

    private static NumberAxis axis(String label) {
        NumberAxis axis = new NumberAxis();
        axis.setLabel(label);
        axis.setForceZeroInRange(true);
        return axis;
    }
}
