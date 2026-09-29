package com.selfdriving.ui.common;

import javafx.event.ActionEvent;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;

/**
 * Changing one's own password: the current one, then the new one twice. The dialog stays open
 * with a message until the change succeeds.
 */
public final class ChangePasswordDialog {

    private ChangePasswordDialog() {
    }

    public static void show(Window owner, ApplicationContext context, Session session) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Change password");
        dialog.setHeaderText("Change the password for " + session.user().username());
        Ui.style(dialog, owner);

        PasswordField current = new PasswordField();
        PasswordField replacement = new PasswordField();
        PasswordField repeat = new PasswordField();
        current.setAccessibleText("Current password");
        replacement.setAccessibleText("New password");
        repeat.setAccessibleText("Repeat new password");
        Label error = Ui.errorLabel();
        Label rules = new Label("At least 8 characters, with letters and at least one digit");
        rules.getStyleClass().add("muted");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        grid.addRow(0, formLabel("Current password", current), current);
        grid.addRow(1, formLabel("New password", replacement), replacement);
        grid.addRow(2, formLabel("Repeat new password", repeat), repeat);
        dialog.getDialogPane().setContent(new VBox(12, grid, rules, error));

        ButtonType change = new ButtonType("Change password", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(change, ButtonType.CANCEL);
        Button ok = (Button) dialog.getDialogPane().lookupButton(change);
        ok.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume(); // close only after the change succeeded
            if (!replacement.getText().equals(repeat.getText())) {
                error.setText("The new passwords do not match");
                return;
            }
            char[] a = current.getText().toCharArray();
            char[] b = replacement.getText().toCharArray();
            ok.setDisable(true);
            error.setText("");
            Ui.background(() -> {
                context.users().changeOwnPassword(session, a, b);
                return null;
            }, done -> {
                dialog.close();
                Ui.inform(owner, "Password changed", "Use the new password the next time you sign in.");
            }, message -> {
                ok.setDisable(false);
                error.setText(message);
            });
        });
        javafx.application.Platform.runLater(current::requestFocus);
        dialog.showAndWait();
    }

    static Label formLabel(String text, javafx.scene.Node field) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        l.setLabelFor(field);
        return l;
    }
}
