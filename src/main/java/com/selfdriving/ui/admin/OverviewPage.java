package com.selfdriving.ui.admin;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import com.selfdriving.auth.Permission;
import com.selfdriving.persistence.AlertRepository;
import com.selfdriving.persistence.AuditRepository;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.DashboardService;
import com.selfdriving.service.Session;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * The administrator's dashboard: the car right now, software, people, trips, alerts and
 * maintenance at a glance, with the latest critical alerts and audit entries. Refreshes every
 * few seconds while open.
 */
public final class OverviewPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final VBox root;
    private final Label car = new Label();
    private final Label carDetail = new Label();
    private final Label version = new Label();
    private final Label versionDetail = new Label();
    private final Label users = new Label();
    private final Label usersDetail = new Label();
    private final Label tripsToday = new Label();
    private final Label tripsDetail = new Label();
    private final Label alerts = new Label();
    private final Label alertsDetail = new Label();
    private final Label issues = new Label();
    private final Label issuesDetail = new Label();
    private final TableView<AlertRepository.Stored> critical = Ui.table("No critical alerts");
    private final TableView<AuditRepository.Entry> audit = Ui.table("Nothing yet");
    private final Label error = Ui.errorLabel();
    private final Timeline timer = new Timeline(new KeyFrame(Duration.seconds(4), e -> refresh()));

    public OverviewPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        timer.setCycleCount(Timeline.INDEFINITE);

        GridPane tiles = new GridPane();
        tiles.setHgap(12);
        tiles.setVgap(12);
        tiles.add(tile("CAR", car, carDetail), 0, 0);
        tiles.add(tile("SOFTWARE", version, versionDetail), 1, 0);
        tiles.add(tile("USERS", users, usersDetail), 2, 0);
        tiles.add(tile("TRIPS TODAY", tripsToday, tripsDetail), 0, 1);
        tiles.add(tile("ALERTS, LAST 24 H", alerts, alertsDetail), 1, 1);
        tiles.add(tile("MAINTENANCE", issues, issuesDetail), 2, 1);
        for (int i = 0; i < 3; i++) {
            javafx.scene.layout.ColumnConstraints c = new javafx.scene.layout.ColumnConstraints();
            c.setPercentWidth(100.0 / 3);
            tiles.getColumnConstraints().add(c);
        }

        critical.getColumns().add(Ui.column("Time", a -> Ui.dateTime(a.createdAt()), 140));
        critical.getColumns().add(Ui.column("Alert", AlertRepository.Stored::message, 300));
        critical.getColumns().add(Ui.column("Acknowledged by", a -> a.acknowledgedBy() == null
                ? (a.acknowledgedAt() == null ? "Not yet" : "") : a.acknowledgedBy(), 120));
        audit.getColumns().add(Ui.column("Time", e -> Ui.dateTime(e.at()), 140));
        audit.getColumns().add(Ui.column("Who", AuditRepository.Entry::username, 80));
        audit.getColumns().add(Ui.column("What", e -> Ui.words(e.action()) + (e.details() == null ? "" : ": "
                + e.details()), 300));
        VBox left = Ui.card("LATEST CRITICAL ALERTS", critical);
        VBox right = Ui.card("RECENT ACTIVITY", audit);
        VBox.setVgrow(critical, Priority.ALWAYS);
        VBox.setVgrow(audit, Priority.ALWAYS);
        HBox lists = new HBox(12, left, right);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setPrefWidth(1);
        right.setPrefWidth(1);

        HBox actions = Ui.row(8);
        if (session.can(Permission.OPEN_DATABASE_CONSOLE)) {
            Button console = Ui.button("Open database console");
            console.setOnAction(e -> {
                Ui.background(() -> {
                    context.openDatabaseConsole(session);
                    return null;
                }, done -> Ui.inform(console.getScene().getWindow(), "Database console",
                        "The H2 console opens in your browser, on this PC only. Close the browser tab to end it."),
                        error::setText);
            });
            actions.getChildren().add(console);
        }
        VBox header = new VBox(8, Ui.row(8, Ui.header("Overview", "The car, the software and the people using it"),
                Ui.spacer(), actions));
        VBox body = new VBox(12, tiles, error, lists);
        VBox.setVgrow(lists, Priority.ALWAYS);
        root = Ui.page(header, body);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        refresh();
        timer.play();
    }

    @Override
    public void hidden() {
        timer.stop();
    }

    private void refresh() {
        showCar(context.simulation().latest());
        Ui.background(() -> context.dashboard().overview(session), this::show, error::setText);
    }

    private void showCar(SimulationSnapshot s) {
        String mode = Ui.words(s.mode().name());
        car.setText(String.format("%.0f km/h", s.vehicle().speedKmh()));
        String faults = s.faults().isEmpty() ? "no faults" : s.faults().size() + " fault(s)";
        carDetail.setText(mode + ", gear " + s.vehicle().gear().letter() + String.format(", battery %.0f %%, ",
                s.vehicle().batteryCharge() * 100) + faults + (s.softwareUpdating() ? ", updating" : ""));
        setClass(car, s.faults().isEmpty() ? null : "stat-warn");
    }

    private void show(DashboardService.Overview o) {
        error.setText("");
        version.setText(o.softwareVersion());
        versionDetail.setText(o.updatesAvailable() == 0 ? "Up to date (as of the last check)"
                : o.updatesAvailable() + " update(s) available");
        users.setText(Integer.toString(o.users()));
        usersDetail.setText(o.lockedUsers() == 0 ? "accounts, none locked" : "accounts, " + o.lockedUsers() + " locked");
        tripsToday.setText(Integer.toString(o.today().trips()));
        tripsDetail.setText(String.format("%.1f km today \u00B7 %.1f km, %.1f kWh in all", o.today().drivenM() / 1000,
                o.allTime().drivenM() / 1000, o.allTime().energyKwh()));
        int[] a = o.alerts24h();
        alerts.setText(Integer.toString(a[0] + a[1] + a[2]));
        alertsDetail.setText(a[2] + " critical \u00B7 " + a[1] + " warnings \u00B7 " + a[0] + " information");
        setClass(alerts, a[2] > 0 ? "stat-bad" : null);
        issues.setText(o.openIssues() + " open");
        issuesDetail.setText(o.lastTestRun() == null ? "No system test run yet"
                : "Last system test " + Ui.dateTime(o.lastTestRun().startedAt()) + ": " + o.lastTestRun().passed()
                + " passed, " + o.lastTestRun().failed() + " failed");
        setClass(issues, o.openIssues() > 0 ? "stat-warn" : null);
        critical.getItems().setAll(o.recentCritical());
        audit.getItems().setAll(o.recentAudit());
    }

    private static VBox tile(String title, Label value, Label detail) {
        detail.getStyleClass().add("stat-caption");
        detail.setWrapText(true);
        value.getStyleClass().add("tile-value");
        VBox card = Ui.card(title, value, detail);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setMinHeight(104);
        return card;
    }

    private static void setClass(Label l, String cls) {
        l.getStyleClass().removeAll("stat-warn", "stat-bad");
        if (cls != null) {
            l.getStyleClass().add(cls);
        }
    }
}
