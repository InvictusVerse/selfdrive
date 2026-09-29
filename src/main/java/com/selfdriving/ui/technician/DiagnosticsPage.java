package com.selfdriving.ui.technician;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

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
import com.selfdriving.diagnostics.DiagnosticReport;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.MaintenanceService;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * Diagnostics: scan every subsystem (faults found open issues), and, with full access, inject
 * faults to see how the car and its driver cope. Repairs are made from the Issues page.
 */
public final class DiagnosticsPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final TableView<DiagnosticReport.Check> table = Ui.table("Press Run scan to check every system");
    private final Label summary = new Label("No scan yet");
    private final Label error = Ui.errorLabel();
    private final Map<Fault, Label> states = new EnumMap<>(Fault.class);
    private final Map<Fault, Button> injectButtons = new EnumMap<>(Fault.class);
    private final Timeline poll = new Timeline(new KeyFrame(Duration.seconds(0.5), e -> showFaults()));
    private final Button scan = Ui.button("Run scan", "primary");
    private final VBox root;

    public DiagnosticsPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        poll.setCycleCount(Timeline.INDEFINITE);

        table.getColumns().add(Ui.column("System", c -> c.subsystem().label(), 140));
        table.getColumns().add(Ui.styledColumn("Status", c -> Ui.words(c.status().name()), s -> switch (s) {
            case "Ok" -> "cell-good";
            case "Warning" -> "cell-warn";
            default -> "cell-bad";
        }, 90));
        table.getColumns().add(Ui.column("Reading", DiagnosticReport.Check::reading, 330));
        table.getColumns().add(Ui.column("Code", c -> c.fault() == null ? "" : c.fault().code(), 70));
        table.getColumns().add(Ui.column("Finding", c -> c.fault() == null ? "" : c.fault().title(), 220));
        table.setFixedCellSize(34);
        table.setPrefHeight(34 * 8 + 44);
        table.setMinHeight(34 * 8 + 44);

        scan.setOnAction(e -> runScan());
        summary.getStyleClass().add("nav-summary");
        VBox scanCard = Ui.card("DIAGNOSTIC SCAN", Ui.row(12, scan, summary), table);

        GridPane faults = new GridPane();
        faults.setHgap(14);
        faults.setVgap(8);
        int row = 0;
        boolean canInject = session.can(Permission.INJECT_FAULTS);
        for (Fault f : Fault.values()) {
            Label code = new Label(f.code());
            code.getStyleClass().add("mono");
            code.setMinWidth(Label.USE_PREF_SIZE);
            Label title = new Label(f.title());
            Label effect = new Label(f.effect());
            effect.getStyleClass().add("muted");
            effect.setWrapText(true);
            Label state = new Label();
            state.setMinWidth(64);
            states.put(f, state);
            faults.add(code, 0, row);
            faults.add(new VBox(2, title, effect), 1, row);
            faults.add(state, 2, row);
            GridPane.setHgrow(effect.getParent(), Priority.ALWAYS);
            if (canInject) {
                Button inject = Ui.button("Inject");
                inject.setAccessibleText("Inject " + f.title());
                inject.setOnAction(e -> Ui.background(() -> {
                    context.maintenance().injectFault(session, f);
                    return null;
                }, done -> showFaults(), error::setText));
                injectButtons.put(f, inject);
                faults.add(inject, 3, row);
            }
            row++;
        }
        VBox faultCard = Ui.card(canInject ? "FAULTS  \u00B7  INJECT TO TEST THE CAR'S REACTION" : "FAULTS", faults,
                muted(canInject ? "An injected fault acts like a real one: the car's behaviour changes and the "
                        + "system tests fail. Run a scan to open an issue, then repair it on the Issues page."
                        : "Fault injection needs full technician access. Repairs are made on the Issues page."));
        HBox body = new HBox(12, scanCard, faultCard);
        HBox.setHgrow(scanCard, Priority.ALWAYS);
        faultCard.setPrefWidth(560);
        faultCard.setMinWidth(480);
        root = Ui.page(Ui.header("Diagnostics", "Check the car's systems and read fault codes"),
                new VBox(12, error, body));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        showFaults();
        poll.play();
    }

    @Override
    public void hidden() {
        poll.stop();
    }

    private void runScan() {
        error.setText("");
        scan.setDisable(true);
        Ui.background(() -> context.maintenance().scan(session), this::showScan, message -> {
            scan.setDisable(false);
            error.setText(message);
        });
    }

    private void showScan(MaintenanceService.ScanResult result) {
        scan.setDisable(false);
        table.getItems().setAll(result.report().checks());
        int faults = result.report().faults().size();
        String text = faults == 0 ? "All systems OK" : faults + " fault(s) found";
        if (!result.opened().isEmpty()) {
            text += ", " + result.opened().size() + " new issue(s) opened";
        }
        summary.setText(text + "  \u00B7  " + Ui.time(context.clock().instant()));
        summary.getStyleClass().removeAll("stat-bad", "stat-good");
        summary.getStyleClass().add(faults == 0 ? "stat-good" : "stat-bad");
    }

    private void showFaults() {
        Set<Fault> present = context.simulation().latest().faults();
        for (Fault f : Fault.values()) {
            boolean on = present.contains(f);
            Label state = states.get(f);
            state.setText(on ? "PRESENT" : "none");
            state.getStyleClass().removeAll("fault-on", "muted");
            state.getStyleClass().add(on ? "fault-on" : "muted");
            Button b = injectButtons.get(f);
            if (b != null) {
                b.setDisable(on);
            }
        }
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }
}
