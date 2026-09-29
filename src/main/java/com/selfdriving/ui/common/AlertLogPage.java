package com.selfdriving.ui.common;

import java.util.List;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.selfdriving.alerts.Alert;
import com.selfdriving.auth.Permission;
import com.selfdriving.persistence.AlertRepository.Stored;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;

/**
 * Every alert the car and the system raised, filtered by severity and category, with who
 * acknowledged it. Critical alerts can be acknowledged here too.
 */
public final class AlertLogPage implements Page {

    private static final int LIMIT = 1000;

    private final ApplicationContext context;
    private final Session session;
    private final TableView<Stored> table = Ui.table("No alerts");
    private final ComboBox<Alert.Severity> severity = new ComboBox<>(FXCollections.observableArrayList(
            Alert.Severity.values()));
    private final ComboBox<Alert.Category> category = new ComboBox<>();
    private final Button acknowledge = Ui.button("Acknowledge");
    private final Label error = Ui.errorLabel();
    private final VBox root;
    private boolean showing;

    public AlertLogPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        table.getColumns().add(Ui.column("Time", a -> Ui.dateTime(a.createdAt()), 150));
        table.getColumns().add(Ui.styledColumn("Severity", a -> Ui.words(a.severity().name()), s -> switch (s) {
            case "Critical" -> "cell-bad";
            case "Warning" -> "cell-warn";
            default -> "cell-muted";
        }, 90));
        table.getColumns().add(Ui.column("Category", a -> Ui.words(a.category().name()), 110));
        table.getColumns().add(Ui.column("Message", Stored::message, 420));
        table.getColumns().add(Ui.column("Source", Stored::source, 130));
        table.getColumns().add(Ui.column("Acknowledged", a -> a.acknowledgedAt() == null ? ""
                : (a.acknowledgedBy() == null ? "" : a.acknowledgedBy() + ", ") + Ui.time(a.acknowledgedAt()), 140));
        VBox.setVgrow(table, Priority.ALWAYS);

        severity.setValue(Alert.Severity.INFO);
        severity.setCellFactory(c -> severityCell());
        severity.setButtonCell(severityCell());
        severity.setAccessibleText("Lowest severity");
        category.getItems().add(null);
        category.getItems().addAll(Alert.Category.values());
        category.setCellFactory(c -> categoryCell());
        category.setButtonCell(categoryCell());
        category.setAccessibleText("Category");
        severity.setOnAction(e -> reload());
        category.setOnAction(e -> reload());

        acknowledge.setVisible(session.can(Permission.ACKNOWLEDGE_ALERTS));
        acknowledge.setDisable(true);
        acknowledge.setOnAction(e -> {
            Stored a = table.getSelectionModel().getSelectedItem();
            if (a != null) {
                Ui.background(() -> {
                    context.history().acknowledge(session, a.id(), context.clock().instant());
                    return null;
                }, done -> reload(), error::setText);
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
                acknowledge.setDisable(b == null || b.acknowledgedAt() != null));

        root = Ui.page(Ui.row(8, Ui.header("Alerts", "Everything the car and the system reported, newest first"),
                Ui.spacer(), severity, category, acknowledge), new VBox(12, error, table));
        context.recorder().onAlertSaved(() -> Platform.runLater(() -> {
            if (showing) {
                reload();
            }
        }));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        showing = true;
        reload();
    }

    @Override
    public void hidden() {
        showing = false;
    }

    private void reload() {
        Alert.Severity s = severity.getValue() == null ? Alert.Severity.INFO : severity.getValue();
        Alert.Category c = category.getValue();
        Ui.background(() -> context.history().alerts(session, LIMIT, s, c), (List<Stored> list) -> {
            error.setText("");
            Stored selected = table.getSelectionModel().getSelectedItem();
            table.getItems().setAll(list);
            if (selected != null) {
                list.stream().filter(x -> x.id() == selected.id()).findFirst()
                        .ifPresent(x -> table.getSelectionModel().select(x));
            }
        }, error::setText);
    }

    private static ListCell<Alert.Severity> severityCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Alert.Severity item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : switch (item) {
                    case INFO -> "All severities";
                    case WARNING -> "Warnings and critical";
                    case CRITICAL -> "Critical only";
                });
            }
        };
    }

    private static ListCell<Alert.Category> categoryCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(Alert.Category item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item == null ? "All categories" : Ui.words(item.name()));
            }
        };
    }
}
