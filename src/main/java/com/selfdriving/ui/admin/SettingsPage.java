package com.selfdriving.ui.admin;

import java.util.LinkedHashMap;
import java.util.Map;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.service.SettingsService;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * System settings: each value with its range and an explanation. Saving checks every value,
 * stores it (audited) and applies it to the car at once.
 */
public final class SettingsPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final Map<String, Control> fields = new LinkedHashMap<>();
    private final Label version = new Label();
    private final Label error = Ui.errorLabel();
    private final Label saved = new Label();
    private final VBox root;

    public SettingsPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(6);
        int row = 0;
        for (SettingsService.Definition d : SettingsService.DEFINITIONS) {
            Label name = new Label(d.label());
            name.getStyleClass().add("setting-name");
            Control field;
            if (d.type() == SettingsService.Type.BOOLEAN) {
                field = new CheckBox("On");
            } else {
                TextField text = new TextField();
                text.setPrefColumnCount(6);
                field = text;
            }
            field.setAccessibleText(d.label());
            name.setLabelFor(field);
            Label unit = new Label(d.type() == SettingsService.Type.BOOLEAN ? "" : range(d));
            unit.getStyleClass().add("muted");
            Label help = new Label(d.help());
            help.getStyleClass().add("muted");
            help.setWrapText(true);
            grid.addRow(row++, name, field, unit);
            grid.add(help, 0, row++, 3, 1);
            fields.put(d.key(), field);
        }

        Button save = Ui.button("Save changes", "primary");
        save.setOnAction(e -> save());
        Button revert = Ui.button("Undo changes");
        revert.setOnAction(e -> load());
        saved.getStyleClass().add("form-ok");
        version.getStyleClass().add("tile-value");
        VBox form = Ui.card("DRIVING AND SAFETY", grid, error, Ui.row(8, save, revert, saved));
        VBox software = Ui.card("SOFTWARE", version, muted("Installed version. Updates are installed on the Updates page."));
        form.setMaxWidth(760);
        software.setMaxWidth(760);
        root = Ui.page(Ui.header("Settings", "Changes apply to the car straight away and are recorded in the audit log"),
                new VBox(12, form, software));
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        load();
    }

    private void load() {
        error.setText("");
        saved.setText("");
        Ui.background(() -> context.settings().all(), values -> {
            values.forEach((k, v) -> {
                Control c = fields.get(k);
                if (c instanceof CheckBox box) {
                    box.setSelected(Boolean.parseBoolean(v));
                } else if (c instanceof TextField text) {
                    text.setText(v);
                }
            });
            version.setText(values.get(SettingsService.SOFTWARE_VERSION));
        }, error::setText);
    }

    private void save() {
        error.setText("");
        saved.setText("");
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((k, c) -> values.put(k, c instanceof CheckBox box ? Boolean.toString(box.isSelected())
                : ((TextField) c).getText()));
        Ui.background(() -> {
            // Check everything first, so a mistake in one field changes nothing.
            for (Map.Entry<String, String> e : values.entrySet()) {
                SettingsService.validate(e.getKey(), e.getValue());
            }
            int changed = 0;
            for (Map.Entry<String, String> e : values.entrySet()) {
                String clean = SettingsService.validate(e.getKey(), e.getValue());
                if (!clean.equals(context.settings().get(e.getKey()))) {
                    context.settings().set(session, e.getKey(), clean);
                    changed++;
                }
            }
            return changed;
        }, changed -> {
            load();
            saved.setText(changed == 0 ? "Nothing to change" : changed + " setting(s) saved and applied");
        }, error::setText);
    }

    private static String range(SettingsService.Definition d) {
        String f = d.type() == SettingsService.Type.INTEGER ? "%.0f" : "%.1f";
        return String.format(f + " to " + f + " %s", d.min(), d.max(), d.unit());
    }

    private static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }
}
