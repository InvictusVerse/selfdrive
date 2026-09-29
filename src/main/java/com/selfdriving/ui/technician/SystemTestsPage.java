package com.selfdriving.ui.technician;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.selfdriving.persistence.MaintenanceRepository.TestResult;
import com.selfdriving.persistence.MaintenanceRepository.TestRun;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.simulation.SystemTests;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * System tests: eight functional tests (sensors, brakes, motor, battery, emergency braking,
 * steering) driven on a copy of the car with its current faults, with pass or fail, the
 * measurement and how long each took. Earlier runs are kept.
 */
public final class SystemTestsPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final TableView<TestResult> results = Ui.table("Press Run all tests");
    private final TableView<TestRun> runs = Ui.table("No runs yet");
    private final Label summary = new Label("Not run yet");
    private final Label error = Ui.errorLabel();
    private final Button run = Ui.button("Run all tests", "primary");
    private final VBox root;

    public SystemTestsPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        results.getColumns().add(Ui.column("Test", TestResult::name, 230));
        results.getColumns().add(Ui.styledColumn("Result", r -> r.passed() ? "Pass" : "Fail",
                s -> s.equals("Pass") ? "cell-good" : "cell-bad", 70));
        results.getColumns().add(Ui.column("Time", r -> r.durationMs() + " ms", 80));
        results.getColumns().add(Ui.column("Measurement", TestResult::message, 420));
        runs.getColumns().add(Ui.column("Run", r -> "#" + r.id(), 60));
        runs.getColumns().add(Ui.column("When", r -> Ui.dateTime(r.startedAt()), 140));
        runs.getColumns().add(Ui.column("By", r -> r.technician() == null ? "" : r.technician(), 110));
        runs.getColumns().add(Ui.styledColumn("Result", r -> r.passed() + " passed, " + r.failed() + " failed",
                s -> s.endsWith(" 0 failed") ? "cell-good" : "cell-bad", 150));
        runs.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> {
            if (r != null) {
                showRun(r);
            }
        });
        VBox.setVgrow(results, Priority.ALWAYS);
        VBox.setVgrow(runs, Priority.ALWAYS);

        run.setOnAction(e -> runAll());
        summary.getStyleClass().add("nav-summary");
        Label about = new Label("Tests: " + String.join(" \u00B7 ", SystemTests.NAMES));
        about.getStyleClass().add("muted");
        about.setWrapText(true);
        VBox current = Ui.card("RESULTS", Ui.row(12, run, summary), about, results);
        VBox history = Ui.card("EARLIER RUNS", runs);
        history.setPrefWidth(500);
        history.setMinWidth(480);
        HBox body = new HBox(12, current, history);
        HBox.setHgrow(current, Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);
        root = Ui.page(Ui.header("System tests", "Each test drives a copy of the car on the empty proving ground, "
                + "so the car in use is not disturbed"), new VBox(12, error, body));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        loadRuns(null);
    }

    private void runAll() {
        error.setText("");
        run.setDisable(true);
        summary.setText("Running\u2026");
        Ui.background(() -> context.maintenance().runSystemTests(session), r -> {
            run.setDisable(false);
            loadRuns(r.id());
        }, message -> {
            run.setDisable(false);
            summary.setText("");
            error.setText(message);
        });
    }

    private void loadRuns(Long select) {
        Ui.background(() -> context.maintenance().testRuns(session, 50), (List<TestRun> list) -> {
            runs.getItems().setAll(list);
            if (!list.isEmpty()) {
                TestRun chosen = select == null ? list.get(0)
                        : list.stream().filter(r -> r.id() == select).findFirst().orElse(list.get(0));
                runs.getSelectionModel().select(chosen);
            }
        }, error::setText);
    }

    private void showRun(TestRun r) {
        results.getItems().setAll(r.results());
        long total = r.results().stream().mapToLong(TestResult::durationMs).sum();
        summary.setText(String.format("Run #%d: %d of %d passed in %.1f s", r.id(), r.passed(), r.results().size(),
                total / 1000.0));
        summary.getStyleClass().removeAll("stat-bad", "stat-good");
        summary.getStyleClass().add(r.failed() == 0 ? "stat-good" : "stat-bad");
    }
}
