package com.selfdriving.ui.common;

import java.lang.System.Logger.Level;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import com.selfdriving.persistence.DataException;
import com.selfdriving.service.AccessDeniedException;
import com.selfdriving.service.ValidationException;

/** Small building blocks shared by the pages: layout, formatting, background work, dialogs. */
public final class Ui {

    private static final System.Logger LOG = System.getLogger(Ui.class.getName());

    public static final String THEME = Objects.requireNonNull(Ui.class.getResource("/com/selfdriving/ui/theme.css"),
            "theme.css missing from resources").toExternalForm();

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ENGLISH)
            .withZone(ZoneId.systemDefault());

    /** Database and hashing work runs here, never on the JavaFX thread. */
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "ui-worker");
        t.setDaemon(true);
        return t;
    });

    private Ui() {
    }

    // ---- Background work --------------------------------------------------------------------

    /**
     * Runs work in the background, then hands the result (or the error) to the JavaFX thread.
     */
    public static <T> void background(Callable<T> work, Consumer<T> onSuccess, Consumer<String> onError) {
        WORKER.execute(() -> {
            try {
                T result = work.call();
                Platform.runLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                String message = message(e);
                Platform.runLater(() -> onError.accept(message));
            }
        });
    }

    /** A message for the user; unexpected errors are logged in full. */
    public static String message(Throwable e) {
        if (e instanceof ValidationException || e instanceof AccessDeniedException) {
            return e.getMessage();
        }
        if (e instanceof DataException) {
            LOG.log(Level.ERROR, "Database error", e);
            return "The database could not be reached. Details are in the log";
        }
        LOG.log(Level.ERROR, "Unexpected error", e);
        return "Something went wrong: " + e.getMessage();
    }

    // ---- Layout -----------------------------------------------------------------------------

    /** Page heading with a one-line explanation. */
    public static VBox header(String title, String subtitle) {
        Label t = new Label(title);
        t.getStyleClass().add("page-title");
        Label s = new Label(subtitle);
        s.getStyleClass().add("page-subtitle");
        s.setWrapText(true);
        return new VBox(2, t, s);
    }

    /** A page: header, then content filling the rest. */
    public static VBox page(Node header, Node content) {
        VBox page = new VBox(16, header, content);
        page.getStyleClass().add("page");
        VBox.setVgrow(content, Priority.ALWAYS);
        return page;
    }

    public static VBox card(String title, Node... content) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        if (title != null) {
            Label l = new Label(title);
            l.getStyleClass().add("card-title");
            card.getChildren().add(l);
        }
        card.getChildren().addAll(content);
        return card;
    }

    public static Button button(String text, String... styleClasses) {
        Button b = new Button(text);
        b.getStyleClass().add("dock-button");
        b.getStyleClass().addAll(styleClasses);
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    public static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    public static HBox row(double spacing, Node... nodes) {
        HBox row = new HBox(spacing, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** A label for form errors; empty text hides it. */
    public static Label errorLabel() {
        Label l = new Label();
        l.getStyleClass().add("form-error");
        l.setWrapText(true);
        l.managedProperty().bind(l.textProperty().isNotEmpty());
        l.visibleProperty().bind(l.textProperty().isNotEmpty());
        return l;
    }

    /** A big number with a caption, for summaries. */
    public static VBox stat(Label value, String caption) {
        value.getStyleClass().add("stat-value");
        Label c = new Label(caption);
        c.getStyleClass().add("stat-caption");
        VBox box = new VBox(2, value, c);
        box.getStyleClass().add("stat");
        return box;
    }

    // ---- Tables -----------------------------------------------------------------------------

    public static <S> TableView<S> table(String placeholder) {
        TableView<S> table = new TableView<>();
        table.setPlaceholder(new Label(placeholder));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return table;
    }

    /** A read-only column showing text. */
    public static <S> TableColumn<S, String> column(String title, Function<S, String> value, double width) {
        TableColumn<S, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        c.setReorderable(false);
        return c;
    }

    /** A text column whose cells get a style class chosen from the row (e.g. a status colour). */
    public static <S> TableColumn<S, String> styledColumn(String title, Function<S, String> value,
                                                         Function<String, String> styleClass, double width) {
        TableColumn<S, String> c = column(title, value, width);
        c.setCellFactory(col -> new TableCell<>() {
            private String applied;

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (applied != null) {
                    getStyleClass().remove(applied);
                    applied = null;
                }
                setText(empty ? null : item);
                if (!empty && item != null) {
                    applied = styleClass.apply(item);
                    if (applied != null) {
                        getStyleClass().add(applied);
                    }
                }
            }
        });
        return c;
    }

    // ---- Formatting -------------------------------------------------------------------------

    public static String dateTime(Instant t) {
        return t == null ? "" : DATE_TIME.format(t);
    }

    public static String time(Instant t) {
        return t == null ? "" : TIME.format(t);
    }

    /** 75 s -> "1 min 15 s", 3700 s -> "1 h 2 min". */
    public static String duration(Double seconds) {
        if (seconds == null) {
            return "";
        }
        long s = Math.round(seconds);
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min " + (s % 60) + " s";
        }
        return (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }

    public static String number(Double value, String format) {
        return value == null ? "" : String.format(Locale.ROOT, format, value);
    }

    /** "IN_PROGRESS" -> "In progress". */
    public static String words(String constant) {
        if (constant == null || constant.isEmpty()) {
            return "";
        }
        String lower = constant.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    // ---- Dialogs ----------------------------------------------------------------------------

    /** Applies the dark theme to a dialog and centres it on the owner window. */
    public static void style(Dialog<?> dialog, Window owner) {
        dialog.getDialogPane().getStylesheets().add(THEME);
        dialog.getDialogPane().getStyleClass().add("sd-dialog");
        if (owner != null) {
            dialog.initOwner(owner);
        }
    }

    /** Asks to confirm something that cannot be undone. */
    public static boolean confirm(Window owner, String title, String text, String action) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, new ButtonType(action,
                javafx.scene.control.ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL);
        alert.setTitle(title);
        alert.setHeaderText(title);
        style(alert, owner);
        return alert.showAndWait().map(b -> b.getButtonData() == javafx.scene.control.ButtonBar.ButtonData.OK_DONE)
                .orElse(false);
    }

    public static void inform(Window owner, String title, String text) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, text, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        style(alert, owner);
        alert.showAndWait();
    }
}
