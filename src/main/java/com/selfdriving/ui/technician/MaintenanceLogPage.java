package com.selfdriving.ui.technician;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import com.selfdriving.persistence.MaintenanceRepository.Entry;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/** The car's maintenance history: scans, injected faults, repairs, verifications and test runs. */
public final class MaintenanceLogPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final TableView<Entry> table = Ui.table("Nothing done to the car yet");
    private final Label error = Ui.errorLabel();
    private final VBox root;

    public MaintenanceLogPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;
        table.getColumns().add(Ui.column("When", e -> Ui.dateTime(e.at()), 150));
        table.getColumns().add(Ui.column("Technician", e -> e.technician() == null ? "" : e.technician(), 130));
        table.getColumns().add(Ui.column("Issue", e -> e.issueId() == null ? "" : "#" + e.issueId(), 60));
        table.getColumns().add(Ui.column("Action", Entry::action, 280));
        table.getColumns().add(Ui.column("Result", Entry::result, 380));
        VBox.setVgrow(table, Priority.ALWAYS);
        Button refresh = Ui.button("Refresh");
        refresh.setOnAction(e -> shown());
        root = Ui.page(Ui.row(8, Ui.header("Maintenance log", "Everything done to the car, newest first"), Ui.spacer(),
                refresh), new VBox(12, error, table));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        Ui.background(() -> context.maintenance().maintenanceLog(session, 1000), table.getItems()::setAll,
                error::setText);
    }
}
