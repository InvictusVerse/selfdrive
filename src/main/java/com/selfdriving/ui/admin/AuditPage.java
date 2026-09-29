package com.selfdriving.ui.admin;

import java.util.List;
import java.util.Locale;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.selfdriving.persistence.AuditRepository.Entry;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/** The audit log: who signed in, changed settings, managed users, installed updates, and so on. */
public final class AuditPage implements Page {

    private static final int LIMIT = 1000;

    private final ApplicationContext context;
    private final Session session;
    private final TableView<Entry> table = Ui.table("Nothing recorded");
    private final TextField filter = new TextField();
    private final javafx.scene.control.Label error = Ui.errorLabel();
    private final VBox root;
    private List<Entry> all = List.of();

    public AuditPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        table.getColumns().add(Ui.column("Time", e -> Ui.dateTime(e.at()), 150));
        table.getColumns().add(Ui.column("User", e -> e.username() == null ? "" : e.username(), 100));
        table.getColumns().add(Ui.styledColumn("Action", e -> Ui.words(e.action()), a -> a.contains("failed")
                || a.contains("locked") || a.contains("refused") ? "cell-warn" : null, 170));
        table.getColumns().add(Ui.column("Details", e -> e.details() == null ? "" : e.details(), 500));
        VBox.setVgrow(table, Priority.ALWAYS);
        filter.setPromptText("Filter by user, action or details");
        filter.setAccessibleText("Filter");
        filter.setPrefColumnCount(28);
        filter.textProperty().addListener((o, a, b) -> apply());
        Button refresh = Ui.button("Refresh");
        refresh.setOnAction(e -> shown());
        root = Ui.page(Ui.row(8, Ui.header("Audit log", "Security-relevant actions, newest first (last "
                + LIMIT + ")"), Ui.spacer(), filter, refresh), new VBox(12, error, table));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        Ui.background(() -> context.history().audit(session, LIMIT), entries -> {
            all = entries;
            apply();
        }, error::setText);
    }

    private void apply() {
        String f = filter.getText().trim().toLowerCase(Locale.ROOT);
        table.getItems().setAll(f.isEmpty() ? all : all.stream().filter(e -> (e.username() + " " + e.action() + " "
                + e.details()).toLowerCase(Locale.ROOT).contains(f)).toList());
    }
}
