package com.selfdriving.ui.admin;

import java.util.List;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.selfdriving.persistence.UpdateRepository.Status;
import com.selfdriving.persistence.UpdateRepository.Update;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * Over-the-air updates: check for new versions, install one (the car must be parked; it is
 * held in Park while installing), watch the download and install progress, roll back.
 */
public final class UpdatesPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final TableView<Update> table = Ui.table("No updates known yet. Press Check for updates");
    private final Label current = new Label();
    private final Label notes = new Label("Select an update to see what it changes");
    private final Label error = Ui.errorLabel();
    private final Button install = Ui.button("Install", "primary");
    private final Button rollBack = Ui.button("Roll back\u2026");
    private final VBox root;
    private boolean showing;
    private long generation;

    public UpdatesPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;

        table.getColumns().add(Ui.column("Version", Update::version, 80));
        table.getColumns().add(Ui.styledColumn("Status", u -> Ui.words(u.status().name()), s -> switch (s) {
            case "Completed" -> "cell-good";
            case "Failed", "Rolled back" -> "cell-warn";
            case "Downloading", "Installing" -> "cell-accent";
            default -> null;
        }, 110));
        TableColumn<Update, Update> progress = new TableColumn<>("Progress");
        progress.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        progress.setCellFactory(c -> new TableCell<>() {
            private final ProgressBar bar = new ProgressBar();

            @Override
            protected void updateItem(Update u, boolean empty) {
                super.updateItem(u, empty);
                if (empty || u == null || u.status() == Status.AVAILABLE) {
                    setGraphic(null);
                    return;
                }
                bar.setProgress(u.status() == Status.COMPLETED ? 1 : u.progress() / 100.0);
                bar.setPrefWidth(110);
                setGraphic(bar);
            }
        });
        progress.setPrefWidth(130);
        table.getColumns().add(progress);
        table.getColumns().add(Ui.column("Installed by", u -> u.deployedBy() == null ? "" : u.deployedBy(), 120));
        table.getColumns().add(Ui.column("Finished", u -> Ui.dateTime(u.finishedAt()), 140));
        table.getColumns().add(Ui.column("Details", u -> u.detail() == null ? "" : u.detail(), 320));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, u) -> select(u));
        VBox.setVgrow(table, Priority.ALWAYS);

        Button check = Ui.button("Check for updates");
        check.setOnAction(e -> Ui.background(() -> context.updates().check(session), list -> reload(),
                error::setText));
        install.setOnAction(e -> install());
        rollBack.setOnAction(e -> rollBack());
        current.getStyleClass().add("tile-value");
        notes.setWrapText(true);
        VBox info = Ui.card("INSTALLED", current, muted("Updates install in order. The car must be stopped in Park; "
                + "it stays in Park until the install finishes. Each package's SHA-256 checksum is checked before "
                + "anything changes."));
        VBox detail = Ui.card("SELECTED UPDATE", notes, Ui.row(8, install, rollBack));
        root = Ui.page(Ui.row(8, Ui.header("Software updates", "Over-the-air updates for the car"), Ui.spacer(), check),
                new VBox(12, Ui.row(12, info, detail), error, table));
        info.setPrefWidth(420);
        javafx.scene.layout.HBox.setHgrow(detail, Priority.ALWAYS);
        select(null);
        context.updates().onChange(() -> Platform.runLater(() -> {
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

    /** Progress events come quickly: only the newest reload's answer is shown. */
    private void reload() {
        long mine = ++generation;
        Ui.background(() -> new State(context.updates().list(session), context.updates().currentVersion()), s -> {
            if (mine == generation) {
                show(s.updates(), s.version());
            }
        }, error::setText);
    }

    private record State(List<Update> updates, String version) {
    }

    private void show(List<Update> updates, String version) {
        Update selected = table.getSelectionModel().getSelectedItem();
        table.getItems().setAll(updates);
        if (selected != null) {
            updates.stream().filter(u -> u.id() == selected.id()).findFirst()
                    .ifPresent(u -> table.getSelectionModel().select(u));
        }
        current.setText(version);
        select(table.getSelectionModel().getSelectedItem());
    }

    private void select(Update u) {
        boolean busy = context.updates().isInstalling();
        if (u == null) {
            notes.setText("Select an update to see what it changes");
            install.setDisable(true);
            rollBack.setDisable(true);
            return;
        }
        notes.setText(u.version() + ": " + u.notes());
        install.setDisable(busy || !(u.status() == Status.AVAILABLE || u.status() == Status.ROLLED_BACK));
        install.setText(u.status() == Status.ROLLED_BACK ? "Install again" : "Install");
        rollBack.setDisable(busy || u.status() != Status.COMPLETED);
    }

    private void install() {
        Update u = table.getSelectionModel().getSelectedItem();
        if (u == null) {
            return;
        }
        error.setText("");
        Ui.background(() -> {
            context.updates().start(session, u.id());
            return null;
        }, done -> reload(), error::setText);
    }

    private void rollBack() {
        Update u = table.getSelectionModel().getSelectedItem();
        if (u == null || !Ui.confirm(root.getScene().getWindow(), "Roll back " + u.version() + "?",
                "The settings this update changed go back to their earlier values and the previous version "
                        + "is reported as installed.", "Roll back")) {
            return;
        }
        Ui.background(() -> context.updates().rollBack(session, u.id()), done -> reload(), error::setText);
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }
}
