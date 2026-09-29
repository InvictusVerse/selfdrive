package com.selfdriving.ui.admin;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import javafx.collections.FXCollections;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.selfdriving.auth.AccessLevel;
import com.selfdriving.auth.Permission;
import com.selfdriving.auth.Role;
import com.selfdriving.auth.User;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.Ui;

/**
 * User management (Admin): list accounts, create them, change role and access level,
 * deactivate, reset passwords and delete. The service enforces the rules (for example that the
 * last full administrator stays); this page shows its messages.
 */
public final class UsersPage implements Page {

    private final ApplicationContext context;
    private final Session session;
    private final TableView<User> table = Ui.table("No users");
    private final Label formTitle = new Label("New user");
    private final TextField username = new TextField();
    private final TextField fullName = new TextField();
    private final ComboBox<Role> role = new ComboBox<>(FXCollections.observableArrayList(Role.values()));
    private final ComboBox<AccessLevel> level = new ComboBox<>(FXCollections.observableArrayList(AccessLevel.values()));
    private final CheckBox active = new CheckBox("Account active");
    private final PasswordField password = new PasswordField();
    private final PasswordField repeat = new PasswordField();
    private final Label passwordLabel = formLabel("Password");
    private final Label repeatLabel = formLabel("Repeat password");
    private final Label permissions = new Label();
    private final Label error = Ui.errorLabel();
    private final Label info = new Label();
    private final Button save = Ui.button("Create user", "primary");
    private final Button resetPassword = Ui.button("Reset password\u2026");
    private final Button delete = Ui.button("Delete\u2026", "danger");
    private final VBox root;
    private User editing;

    public UsersPage(ApplicationContext context, Session session) {
        this.context = context;
        this.session = session;

        table.getColumns().add(Ui.column("Username", User::username, 110));
        table.getColumns().add(Ui.column("Name", User::fullName, 150));
        table.getColumns().add(Ui.column("Role", u -> u.role().label(), 170));
        table.getColumns().add(Ui.column("Access", u -> u.accessLevel().label(), 80));
        table.getColumns().add(Ui.styledColumn("Status", UsersPage::status, s -> switch (s) {
            case "Active" -> "cell-good";
            case "Deactivated" -> "cell-muted";
            default -> "cell-bad";
        }, 100));
        table.getColumns().add(Ui.column("Last sign-in", u -> Ui.dateTime(u.lastLogin()), 150));
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, user) -> edit(user));
        VBox.setVgrow(table, Priority.ALWAYS);

        role.setCellFactory(c -> labelled(Role::label));
        role.setButtonCell(labelled(Role::label));
        level.setCellFactory(c -> labelled(AccessLevel::label));
        level.setButtonCell(labelled(AccessLevel::label));
        role.valueProperty().addListener((o, a, b) -> showPermissions());
        level.valueProperty().addListener((o, a, b) -> showPermissions());
        role.setMaxWidth(Double.MAX_VALUE);
        level.setMaxWidth(Double.MAX_VALUE);
        username.setAccessibleText("Username");
        fullName.setAccessibleText("Full name");
        password.setAccessibleText("Password");
        repeat.setAccessibleText("Repeat password");
        permissions.setWrapText(true);
        permissions.getStyleClass().add("muted");
        info.getStyleClass().add("form-info");
        info.setWrapText(true);
        info.managedProperty().bind(info.textProperty().isNotEmpty());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, formLabel("Username"), username);
        grid.addRow(1, formLabel("Full name"), fullName);
        grid.addRow(2, formLabel("Role"), role);
        grid.addRow(3, formLabel("Access level"), level);
        grid.addRow(4, new Label(), active);
        grid.addRow(5, passwordLabel, password);
        grid.addRow(6, repeatLabel, repeat);
        for (Node n : List.of(username, fullName, role, level, password, repeat)) {
            GridPane.setHgrow(n, Priority.ALWAYS);
        }
        for (Node n : List.of(password, repeat, passwordLabel, repeatLabel)) {
            n.managedProperty().bind(n.visibleProperty());
        }

        Button newUser = Ui.button("New user");
        newUser.setOnAction(e -> table.getSelectionModel().clearSelection());
        save.setOnAction(e -> save());
        resetPassword.setOnAction(e -> resetPassword());
        delete.setOnAction(e -> delete());
        formTitle.getStyleClass().add("card-title");
        Label permissionsTitle = new Label("CAN");
        permissionsTitle.getStyleClass().add("card-title");
        VBox form = new VBox(10, Ui.row(8, formTitle, Ui.spacer(), newUser), grid, permissionsTitle, permissions,
                error, info, Ui.row(8, save, Ui.spacer(), resetPassword, delete));
        form.getStyleClass().add("card");
        form.setPrefWidth(400);
        form.setMinWidth(360);

        HBox body = new HBox(16, table, form);
        HBox.setHgrow(table, Priority.ALWAYS);
        root = Ui.page(Ui.header("Users", "Accounts, roles and access levels. Standard access covers everyday work; "
                + "full access adds the more powerful tools of the role"), body);
        edit(null);
    }

    @Override
    public Node node() {
        return root;
    }

    @Override
    public void shown() {
        reload(null);
    }

    private void reload(Long select) {
        Ui.background(() -> context.users().list(session), users -> {
            table.getItems().setAll(users);
            if (select != null) {
                users.stream().filter(u -> u.id() == select).findFirst()
                        .ifPresent(u -> table.getSelectionModel().select(u));
            }
        }, error::setText);
    }

    private void edit(User user) {
        editing = user;
        error.setText("");
        boolean creating = user == null;
        formTitle.setText(creating ? "NEW USER" : "EDIT " + user.username().toUpperCase(Locale.ROOT));
        username.setText(creating ? "" : user.username());
        username.setDisable(!creating);
        fullName.setText(creating ? "" : user.fullName());
        role.setValue(creating ? Role.DRIVER : user.role());
        level.setValue(creating ? AccessLevel.STANDARD : user.accessLevel());
        active.setSelected(creating || user.active());
        active.setDisable(creating);
        password.clear();
        repeat.clear();
        for (Node n : List.of(password, repeat, passwordLabel, repeatLabel)) {
            n.setVisible(creating);
        }
        save.setText(creating ? "Create user" : "Save changes");
        resetPassword.setDisable(creating);
        delete.setDisable(creating || user.id() == session.userId());
        if (!creating && user.lockedUntil() != null && user.lockedUntil().isAfter(context.clock().instant())) {
            info.setText("Locked after wrong passwords until " + Ui.time(user.lockedUntil())
                    + ". Resetting the password unlocks it now.");
        } else {
            info.setText("");
        }
    }

    private void showPermissions() {
        if (role.getValue() == null || level.getValue() == null) {
            return;
        }
        permissions.setText(role.getValue().permissions(level.getValue()).stream().sorted()
                .map(UsersPage::describe).collect(Collectors.joining(" \u00B7 ")));
    }

    private void save() {
        error.setText("");
        if (editing == null) {
            if (!password.getText().equals(repeat.getText())) {
                error.setText("The passwords do not match");
                return;
            }
            String name = username.getText();
            String full = fullName.getText();
            char[] pass = password.getText().toCharArray();
            Role r = role.getValue();
            AccessLevel l = level.getValue();
            Ui.background(() -> context.users().create(session, name, full, pass, r, l),
                    created -> reload(created.id()), error::setText);
        } else {
            long id = editing.id();
            String full = fullName.getText();
            Role r = role.getValue();
            AccessLevel l = level.getValue();
            boolean on = active.isSelected();
            Ui.background(() -> context.users().update(session, id, full, r, l, on), updated -> reload(updated.id()),
                    error::setText);
        }
    }

    private void resetPassword() {
        if (editing == null) {
            return;
        }
        User user = editing;
        Window owner = root.getScene().getWindow();
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Reset password");
        dialog.setHeaderText("New password for " + user.username());
        Ui.style(dialog, owner);
        PasswordField first = new PasswordField();
        PasswordField second = new PasswordField();
        first.setAccessibleText("New password");
        second.setAccessibleText("Repeat new password");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.addRow(0, formLabel("New password"), first);
        grid.addRow(1, formLabel("Repeat"), second);
        Label note = new Label("At least 8 characters with letters and a digit. The account is unlocked too.");
        note.getStyleClass().add("muted");
        dialog.getDialogPane().setContent(new VBox(10, grid, note));
        ButtonType set = new ButtonType("Set password", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(set, ButtonType.CANCEL);
        javafx.application.Platform.runLater(first::requestFocus);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != set) {
            return;
        }
        if (!first.getText().equals(second.getText())) {
            error.setText("The passwords do not match");
            return;
        }
        char[] pass = first.getText().toCharArray();
        Ui.background(() -> {
            context.users().resetPassword(session, user.id(), pass);
            return null;
        }, done -> {
            reload(user.id());
            Ui.inform(owner, "Password reset", "Tell " + user.fullName() + " the new password in person.");
        }, error::setText);
    }

    private void delete() {
        if (editing == null) {
            return;
        }
        User user = editing;
        Window owner = root.getScene().getWindow();
        if (!Ui.confirm(owner, "Delete " + user.username() + "?", "The account is removed. Trips and log entries stay, "
                + "without the name. To keep the account for later, deactivate it instead.", "Delete")) {
            return;
        }
        Ui.background(() -> {
            context.users().delete(session, user.id());
            return null;
        }, done -> {
            table.getSelectionModel().clearSelection();
            reload(null);
        }, error::setText);
    }

    private static String status(User u) {
        if (!u.active()) {
            return "Deactivated";
        }
        if (u.lockedUntil() != null && u.lockedUntil().isAfter(java.time.Instant.now())) {
            return "Locked";
        }
        return "Active";
    }

    /** Human wording for a permission. */
    static String describe(Permission p) {
        return switch (p) {
            case DRIVE -> "drive";
            case VIEW_OWN_TRIPS -> "see own trips";
            case VIEW_ALL_TRIPS -> "see all trips";
            case RUN_SCENARIOS -> "run test scenarios";
            case VIEW_ALERTS -> "see alerts";
            case ACKNOWLEDGE_ALERTS -> "acknowledge alerts";
            case MANAGE_USERS -> "manage users";
            case EDIT_SETTINGS -> "change settings";
            case DEPLOY_UPDATES -> "install updates";
            case VIEW_PERFORMANCE -> "see performance";
            case VIEW_AUDIT_LOG -> "see the audit log";
            case OPEN_DATABASE_CONSOLE -> "open the database console";
            case RUN_DIAGNOSTICS -> "run diagnostics";
            case APPLY_FIXES -> "apply fixes";
            case RUN_SYSTEM_TESTS -> "run system tests";
            case MANAGE_ISSUES -> "track issues";
            case INJECT_FAULTS -> "inject faults";
        };
    }

    private static Label formLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        l.setMinWidth(Label.USE_PREF_SIZE);
        return l;
    }

    private static <T> ListCell<T> labelled(java.util.function.Function<T, String> text) {
        return new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
            }
        };
    }
}
