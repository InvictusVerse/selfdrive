package com.selfdriving.ui.technician;

import java.util.List;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.selfdriving.auth.Permission;
import com.selfdriving.diagnostics.Fault;
import com.selfdriving.diagnostics.Subsystem;
import com.selfdriving.persistence.IssueRepository;
import com.selfdriving.persistence.IssueRepository.Issue;
import com.selfdriving.persistence.IssueRepository.Status;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * Issue tracking. Each issue moves Open → In progress → Fixed → Verified → Closed; the panel
 * on the right offers the next step for the selected issue. Fixing a fault issue repairs the
 * car; verifying runs a fresh scan and reopens the issue if the fault is still there.
 */
public final class IssuesPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final TableView<Issue> table = Ui.table("No open issues. Run a diagnostic scan to look for faults");
    private final CheckBox showClosed = new CheckBox("Show closed");
    private final Label title = new Label("Select an issue");
    private final Label detail = new Label();
    private final Label steps = new Label();
    private final TextField note = new TextField();
    private final Button next = Ui.button("", "primary");
    private final Label error = Ui.errorLabel();
    private final VBox root;
    private Issue selected;

    public IssuesPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        table.getColumns().add(Ui.column("#", i -> Long.toString(i.id()), 45));
        table.getColumns().add(Ui.column("Code", i -> IssueRepository.MANUAL_CODE.equals(i.faultCode()) ? "reported"
                : i.faultCode(), 75));
        table.getColumns().add(Ui.column("System", Issue::subsystem, 120));
        table.getColumns().add(Ui.styledColumn("Severity", i -> Ui.words(i.severity()), s -> s.equals("Critical")
                ? "cell-bad" : "cell-warn", 80));
        table.getColumns().add(Ui.column("Description", Issue::description, 320));
        table.getColumns().add(Ui.styledColumn("Status", i -> Ui.words(i.status().name()), s -> switch (s) {
            case "Open" -> "cell-bad";
            case "In progress" -> "cell-accent";
            case "Fixed" -> "cell-warn";
            default -> "cell-good";
        }, 95));
        table.getColumns().add(Ui.column("Assigned", i -> i.assignedName() == null ? "" : i.assignedName(), 110));
        table.getColumns().add(Ui.column("Found", i -> Ui.dateTime(i.detectedAt()), 140));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, i) -> select(i));
        VBox.setVgrow(table, Priority.ALWAYS);

        showClosed.setOnAction(e -> reload(null));
        Button report = Ui.button("Report an issue\u2026");
        report.setOnAction(e -> reportIssue());
        title.getStyleClass().add("nav-summary");
        title.setWrapText(true);
        detail.setWrapText(true);
        detail.getStyleClass().add("muted");
        steps.setWrapText(true);
        steps.getStyleClass().add("form-label");
        note.setPromptText("What was done (for reported issues)");
        note.setAccessibleText("Repair note");
        note.managedProperty().bind(note.visibleProperty());
        next.setOnAction(e -> advance());
        next.setMaxWidth(Double.MAX_VALUE);
        VBox panel = Ui.card("SELECTED ISSUE", title, detail, steps, note, next, error);
        panel.setPrefWidth(380);
        panel.setMinWidth(340);
        HBox body = new HBox(12, table, panel);
        HBox.setHgrow(table, Priority.ALWAYS);
        root = Ui.page(Ui.row(8, Ui.header("Issues", "Faults found by scans and problems reported by people"),
                Ui.spacer(), showClosed, report), body);
        select(null);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        reload(selected == null ? null : selected.id());
    }

    private void reload(Long keep) {
        Ui.background(() -> context.maintenance().issues(session, showClosed.isSelected()), (List<Issue> list) -> {
            table.getItems().setAll(list);
            Issue again = keep == null ? null : list.stream().filter(i -> i.id() == keep).findFirst().orElse(null);
            if (again != null) {
                table.getSelectionModel().select(again);
            } else {
                table.getSelectionModel().clearSelection();
                select(null);
            }
        }, error::setText);
    }

    private void select(Issue i) {
        selected = i;
        error.setText("");
        note.setVisible(false);
        if (i == null) {
            title.setText("Select an issue");
            detail.setText("");
            steps.setText("Open \u2192 In progress \u2192 Fixed \u2192 Verified \u2192 Closed");
            next.setVisible(false);
            return;
        }
        boolean fault = !IssueRepository.MANUAL_CODE.equals(i.faultCode());
        title.setText("#" + i.id() + (fault ? "  " + i.faultCode() : "") + "  \u00B7  " + i.subsystem());
        String text = i.description();
        if (i.resolution() != null) {
            text += "\nRepair: " + i.resolution();
        }
        if (fault && i.status() == Status.IN_PROGRESS) {
            text += "\nFix to apply: " + Fault.byCode(i.faultCode()).fix();
        }
        detail.setText(text);
        steps.setText(progress(i.status()));
        next.setVisible(true);
        next.setDisable(false);
        switch (i.status()) {
            case OPEN -> next.setText("Start work (assign to me)");
            case IN_PROGRESS -> {
                next.setText(fault ? "Apply the fix" : "Mark as fixed");
                note.setVisible(!fault);
                next.setDisable(!session.can(Permission.APPLY_FIXES));
            }
            case FIXED -> next.setText(fault ? "Verify with a new scan" : "Verify");
            case VERIFIED -> next.setText("Close the issue");
            case CLOSED -> next.setVisible(false);
            default -> { }
        }
    }

    private void advance() {
        Issue i = selected;
        if (i == null) {
            return;
        }
        String text = note.getText();
        next.setDisable(true);
        Ui.background(() -> switch (i.status()) {
            case OPEN -> context.maintenance().startWork(session, i.id());
            case IN_PROGRESS -> context.maintenance().fix(session, i.id(), text);
            case FIXED -> context.maintenance().verify(session, i.id());
            case VERIFIED -> context.maintenance().close(session, i.id());
            default -> i;
        }, updated -> {
            note.clear();
            if (i.status() == Status.FIXED && updated.status() == Status.OPEN) {
                Ui.inform(root.getScene().getWindow(), "Verification failed",
                        "The fault is still present, so issue #" + i.id() + " has been reopened.");
            }
            reload(updated.status() == Status.CLOSED && !showClosed.isSelected() ? null : updated.id());
        }, message -> {
            next.setDisable(false);
            error.setText(message);
        });
    }

    private void reportIssue() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Report an issue");
        dialog.setHeaderText("Report something wrong with the car");
        Ui.style(dialog, root.getScene().getWindow());
        ComboBox<String> system = new ComboBox<>(FXCollections.observableArrayList(
                java.util.Arrays.stream(Subsystem.values()).map(Subsystem::label).toList()));
        system.setValue(Subsystem.STEERING.label());
        system.setAccessibleText("System");
        TextArea text = new TextArea();
        text.setPrefRowCount(3);
        text.setWrapText(true);
        text.setAccessibleText("Description");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, new Label("System"), system);
        grid.addRow(1, new Label("What is wrong"), text);
        dialog.getDialogPane().setContent(grid);
        ButtonType save = new ButtonType("Report", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != save) {
            return;
        }
        String subsystem = system.getValue();
        String description = text.getText();
        Ui.background(() -> context.maintenance().report(session, subsystem, description), i -> reload(i.id()),
                error::setText);
    }

    private static String progress(Status s) {
        StringBuilder sb = new StringBuilder();
        for (Status step : Status.values()) {
            if (!sb.isEmpty()) {
                sb.append("  \u2192  ");
            }
            String name = Ui.words(step.name());
            sb.append(step == s ? "[" + name + "]" : name);
        }
        return sb.toString();
    }
}
