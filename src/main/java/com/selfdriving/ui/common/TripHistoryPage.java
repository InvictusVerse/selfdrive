package com.selfdriving.ui.common;

import java.util.List;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import com.selfdriving.auth.Permission;
import com.selfdriving.persistence.TripRepository.Trip;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;

/**
 * Trip history: every route driven, with distance, time, energy and how much of it the
 * autopilot drove. Drivers see their own trips; users allowed to see all trips also see who
 * drove. Refreshes by itself when a trip is saved.
 */
public final class TripHistoryPage implements Page {

    private static final int LIMIT = 500;

    private final ApplicationContext context;
    private final Session session;
    private final TableView<Trip> table = Ui.table("No trips yet. Choose a destination on the Drive page and press Go");
    private final Label count = new Label("0");
    private final Label distance = new Label("0");
    private final Label energy = new Label("0");
    private final Label consumption = new Label("-");
    private final Label autopilot = new Label("-");
    private final Label error = Ui.errorLabel();
    private final VBox root;
    private final Runnable refreshLater = () -> Platform.runLater(this::refreshIfShowing);
    private boolean showing;

    public TripHistoryPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        boolean everyone = session.can(Permission.VIEW_ALL_TRIPS);

        table.getColumns().add(Ui.column("Started", t -> Ui.dateTime(t.startedAt()), 150));
        if (everyone) {
            table.getColumns().add(Ui.column("Driver", t -> t.driverName() == null ? "(deleted user)" : t.driverName(), 120));
        }
        table.getColumns().add(Ui.column("From", Trip::origin, 170));
        table.getColumns().add(Ui.column("To", Trip::destination, 170));
        table.getColumns().add(Ui.column("Planned", t -> Ui.number(t.plannedM() / 1000, "%.2f km"), 80));
        table.getColumns().add(Ui.column("Driven", t -> t.drivenM() == null ? "" : Ui.number(t.drivenM() / 1000, "%.2f km"), 80));
        table.getColumns().add(Ui.column("Time", t -> Ui.duration(t.durationS()), 90));
        table.getColumns().add(Ui.column("Energy", t -> Ui.number(t.energyKwh(), "%.2f kWh"), 80));
        table.getColumns().add(Ui.column("Wh/km", TripHistoryPage::whPerKm, 70));
        table.getColumns().add(Ui.column("Autopilot", t -> t.autopilotShare() == null ? ""
                : Ui.number(t.autopilotShare() * 100, "%.0f %%"), 80));
        table.getColumns().add(Ui.styledColumn("Status", t -> Ui.words(t.status()), TripHistoryPage::statusClass, 100));

        Button refresh = Ui.button("Refresh");
        refresh.setOnAction(e -> refresh());
        HBox stats = Ui.row(28, Ui.stat(count, "trips"), Ui.stat(distance, "km driven"), Ui.stat(energy, "kWh used"),
                Ui.stat(consumption, "average Wh/km"), Ui.stat(autopilot, "on autopilot"), Ui.spacer(), refresh);
        stats.getStyleClass().addAll("card", "stats-row");

        root = Ui.page(Ui.header(everyone ? "Trips" : "My trips", everyone
                ? "Every trip by every driver, newest first"
                : "Your trips, newest first. A trip starts when you plan a route and ends on arrival or when the route is cancelled"),
                new VBox(12, stats, error, table));
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        context.recorder().onTripSaved(refreshLater);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        showing = true;
        refresh();
    }

    @Override
    public void hidden() {
        showing = false;
    }

    private void refreshIfShowing() {
        if (showing) {
            refresh();
        }
    }

    private void refresh() {
        Ui.background(() -> context.history().trips(session, LIMIT), this::show, error::setText);
    }

    private void show(List<Trip> trips) {
        error.setText("");
        table.getItems().setAll(trips);
        double km = 0;
        double kwh = 0;
        double time = 0;
        double autopilotTime = 0;
        for (Trip t : trips) {
            if (t.drivenM() != null) {
                km += t.drivenM() / 1000;
                kwh += t.energyKwh();
                time += t.durationS();
                autopilotTime += t.autopilotShare() * t.durationS();
            }
        }
        count.setText(Integer.toString(trips.size()));
        distance.setText(String.format("%.1f", km));
        energy.setText(String.format("%.2f", kwh));
        consumption.setText(km > 0.1 ? String.format("%.0f", kwh * 1000 / km) : "-");
        autopilot.setText(time > 0 ? String.format("%.0f %%", 100 * autopilotTime / time) : "-");
    }

    private static String whPerKm(Trip t) {
        if (t.drivenM() == null || t.drivenM() < 100) {
            return "";
        }
        return String.format("%.0f", t.energyKwh() * 1000 / (t.drivenM() / 1000));
    }

    private static String statusClass(String status) {
        return switch (status) {
            case "Arrived" -> "cell-good";
            case "Cancelled" -> "cell-muted";
            default -> "cell-accent";
        };
    }
}
