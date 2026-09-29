package com.selfdriving.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.selfdriving.auth.Permission;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.ui.admin.UsersPage;
import com.selfdriving.ui.common.ChangePasswordDialog;
import com.selfdriving.ui.common.Page;
import com.selfdriving.ui.common.TripHistoryPage;
import com.selfdriving.ui.driver.DriverScreen;

/**
 * The signed-in window: a navigation rail on the left with the pages the user's role and
 * access level allow, the current page on the right, and the user's name, password change and
 * sign-out at the bottom of the rail. Pages are built the first time they are opened.
 */
public final class AppShell {

    /** A rail entry. */
    private record Entry(String id, String label, String icon, Predicate<Session> allowed,
                         Function<AppShell, Page> factory) {
    }

    private final ApplicationContext context;
    private final Session session;
    private final DriverScreen driverScreen;
    private final Runnable onLogout;
    private final BorderPane root = new BorderPane();
    private final StackPane content = new StackPane();
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final List<Entry> entries = new ArrayList<>();
    private String current;

    public AppShell(ApplicationContext context, Session session, DriverScreen driverScreen, Runnable onLogout) {
        this.context = context;
        this.session = session;
        this.driverScreen = driverScreen;
        this.onLogout = onLogout;

        entries.add(new Entry("drive", "Drive", NavIcons.DRIVE, s -> s.can(Permission.DRIVE),
                shell -> new DrivePage(shell.driverScreen, shell.session)));
        entries.add(new Entry("trips", "Trips", NavIcons.TRIPS,
                s -> s.can(Permission.VIEW_OWN_TRIPS) || s.can(Permission.VIEW_ALL_TRIPS),
                shell -> new TripHistoryPage(shell.context, shell.session)));
        entries.add(new Entry("users", "Users", NavIcons.USERS, s -> s.can(Permission.MANAGE_USERS),
                shell -> new UsersPage(shell.context, shell.session)));

        VBox rail = new VBox(4);
        rail.getStyleClass().add("rail");
        rail.setAlignment(Pos.TOP_CENTER);
        for (Entry e : entries) {
            if (!e.allowed().test(session)) {
                continue;
            }
            Button b = railButton(e.label(), e.icon(), e.label());
            b.setOnAction(ev -> show(e.id()));
            buttons.put(e.id(), b);
            rail.getChildren().add(b);
        }
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        Label avatar = new Label(initials(session.user().fullName()));
        avatar.getStyleClass().add("avatar");
        Tooltip.install(avatar, new Tooltip(session.user().fullName() + "\n" + session.user().role().label() + ", "
                + session.user().accessLevel().label().toLowerCase(Locale.ROOT) + " access"));
        avatar.setAccessibleText("Signed in as " + session.user().fullName());
        Label role = new Label(shortRole());
        role.getStyleClass().add("rail-role");
        Button password = railButton("Password", NavIcons.PASSWORD, "Change your password");
        password.setOnAction(e -> ChangePasswordDialog.show(root.getScene().getWindow(), context, session));
        Button logout = railButton("Sign out", NavIcons.LOGOUT, "Sign out");
        logout.setOnAction(e -> onLogout.run());
        rail.getChildren().addAll(spacer, avatar, role, password, logout);

        content.getStyleClass().add("shell-content");
        content.setMinSize(0, 0);
        root.setLeft(rail);
        root.setCenter(content);

        if (!buttons.isEmpty()) {
            show(buttons.keySet().iterator().next());
        } else {
            Label none = new Label("This account has no pages. Ask an administrator for access.");
            none.getStyleClass().add("page-subtitle");
            content.getChildren().setAll(none);
        }
    }

    public Parent node() {
        return root;
    }

    /** Opens a page by id ("drive", "trips", "users", ...) if the user may see it. */
    public boolean show(String id) {
        Button button = buttons.get(id);
        if (button == null) {
            return false;
        }
        if (id.equals(current)) {
            return true;
        }
        if (current != null) {
            pages.get(current).hidden();
            buttons.get(current).getStyleClass().remove("selected");
        }
        Entry entry = entries.stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        Page page = pages.computeIfAbsent(id, k -> entry.factory().apply(this));
        content.getChildren().setAll(page.node());
        button.getStyleClass().add("selected");
        current = id;
        page.shown();
        return true;
    }

    /** Leaves every page (on sign-out). */
    public void dispose() {
        if (current != null) {
            pages.get(current).hidden();
        }
        for (Page p : pages.values()) {
            p.dispose();
        }
        content.getChildren().clear();
        current = null;
    }

    public Session session() {
        return session;
    }

    private String shortRole() {
        return switch (session.user().role()) {
            case ADMIN -> "Admin";
            case DRIVER -> "Driver";
            case TECHNICIAN -> "Technician";
        };
    }

    private static Button railButton(String text, String icon, String accessible) {
        Button b = new Button(text, NavIcons.icon(icon));
        b.setContentDisplay(ContentDisplay.TOP);
        b.getStyleClass().add("rail-button");
        b.setAccessibleText(accessible);
        b.setTooltip(new Tooltip(accessible));
        b.setFocusTraversable(false);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && sb.length() < 2) {
                sb.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return sb.isEmpty() ? "?" : sb.toString();
    }

    /** The driver's display as a page. */
    private static final class DrivePage implements Page {

        private final DriverScreen screen;
        private final Session session;

        DrivePage(DriverScreen screen, Session session) {
            this.screen = screen;
            this.session = session;
        }

        @Override
        public Node node() {
            return screen.node();
        }

        @Override
        public void shown() {
            screen.setScenariosAllowed(session.can(Permission.RUN_SCENARIOS));
            screen.setActive(true);
            screen.node().requestFocus();
        }

        @Override
        public void hidden() {
            screen.setActive(false);
        }

        @Override
        public void dispose() {
            if (screen.node().getParent() instanceof Pane parent) {
                parent.getChildren().remove(screen.node());
            }
        }
    }
}
