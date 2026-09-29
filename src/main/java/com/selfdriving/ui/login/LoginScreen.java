package com.selfdriving.ui.login;

import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.AuthService;
import com.selfdriving.service.DemoData;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Ui;

/**
 * Sign-in: username and password, Enter to submit. The check runs in the background (a
 * password hash takes a moment by design). While the demo accounts still have their published
 * passwords they are listed underneath; clicking one fills the form.
 */
public final class LoginScreen {

    private final ApplicationContext context;
    private final Consumer<Session> onLogin;
    private final StackPane root = new StackPane();
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final Button signIn = Ui.button("Sign in", "primary", "wide");
    private final Label error = Ui.errorLabel();
    private final VBox demo = new VBox(6);

    public LoginScreen(ApplicationContext context, String appName, String version, Consumer<Session> onLogin) {
        this.context = context;
        this.onLogin = onLogin;

        Label title = new Label(appName);
        title.getStyleClass().add("login-title");
        Label subtitle = new Label("Sign in to continue  \u00B7  v" + version);
        subtitle.getStyleClass().add("page-subtitle");

        username.setPromptText("Username");
        username.setAccessibleText("Username");
        password.setPromptText("Password");
        password.setAccessibleText("Password");
        Label userLabel = new Label("Username");
        userLabel.getStyleClass().add("form-label");
        userLabel.setLabelFor(username);
        Label passLabel = new Label("Password");
        passLabel.getStyleClass().add("form-label");
        passLabel.setLabelFor(password);

        signIn.setDefaultButton(true);
        signIn.setMaxWidth(Double.MAX_VALUE);
        signIn.setOnAction(e -> submit());
        username.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                password.requestFocus();
                e.consume();
            }
        });

        demo.getStyleClass().add("demo-box");
        demo.setVisible(false);
        demo.managedProperty().bind(demo.visibleProperty());

        VBox card = new VBox(10, title, subtitle, gap(8), userLabel, username, passLabel, password, error, gap(4),
                signIn, demo);
        card.getStyleClass().addAll("card", "login-card");
        card.setMaxSize(400, VBox.USE_PREF_SIZE);
        root.getChildren().add(card);
        root.getStyleClass().add("login-screen");
        StackPane.setAlignment(card, Pos.CENTER);

        Ui.background(context::unchangedDemoAccounts, this::showDemoAccounts, message -> { });
    }

    public Parent node() {
        return root;
    }

    /** Clears the form (after logout) and puts the cursor in the username field. */
    public void reset(String message) {
        password.clear();
        error.setText(message == null ? "" : message);
        setBusy(false);
        Platform.runLater(() -> (username.getText().isEmpty() ? username : password).requestFocus());
    }

    /** Signs in directly (developer scripts). */
    public void signIn(String user, String pass) {
        username.setText(user);
        password.setText(pass);
        submit();
    }

    private void submit() {
        String user = username.getText();
        char[] pass = password.getText().toCharArray();
        password.clear();
        if (user.isBlank() || pass.length == 0) {
            error.setText("Enter your username and password");
            return;
        }
        error.setText("");
        setBusy(true);
        Ui.background(() -> context.login(user, pass), this::finish, message -> {
            setBusy(false);
            error.setText(message);
        });
    }

    private void finish(AuthService.Result result) {
        setBusy(false);
        if (result.ok()) {
            error.setText("");
            onLogin.accept(result.session());
        } else {
            error.setText(result.message());
            password.requestFocus();
        }
    }

    private void setBusy(boolean busy) {
        signIn.setDisable(busy);
        signIn.setText(busy ? "Signing in\u2026" : "Sign in");
        username.setDisable(busy);
        password.setDisable(busy);
    }

    private void showDemoAccounts(List<DemoData.Account> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        Label heading = new Label("Demo accounts (listed until their passwords are changed)");
        heading.getStyleClass().add("card-title");
        heading.setWrapText(true);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);
        int row = 0;
        for (DemoData.Account a : accounts) {
            Button pick = Ui.button(a.role().label(), "demo-account");
            pick.setMaxWidth(Double.MAX_VALUE);
            pick.setOnAction(e -> {
                username.setText(a.username());
                password.setText(a.password());
                password.requestFocus();
            });
            Label credentials = new Label(a.username() + "  /  " + a.password());
            credentials.getStyleClass().add("mono");
            grid.addRow(row++, pick, credentials);
        }
        demo.getChildren().setAll(heading, grid);
        demo.setVisible(true);
    }

    private static javafx.scene.layout.Region gap(double h) {
        javafx.scene.layout.Region r = new javafx.scene.layout.Region();
        r.setMinHeight(h);
        return r;
    }
}
