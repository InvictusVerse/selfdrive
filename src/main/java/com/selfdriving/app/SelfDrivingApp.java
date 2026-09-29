package com.selfdriving.app;

import java.util.Objects;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * JavaFX application bootstrap. Creates the main window and applies the dark theme.
 *
 * <p>For now it shows a placeholder screen confirming the project runs. The login screen
 * and role dashboards replace it in later stages.
 */
public final class SelfDrivingApp extends Application {

    public static final String APP_NAME = "Self-Driving Car Control System";
    public static final String VERSION = "0.1.0";

    private static final double WINDOW_WIDTH = 1600;
    private static final double WINDOW_HEIGHT = 900;

    @Override
    public void start(Stage stage) {
        Label title = new Label(APP_NAME);
        title.getStyleClass().add("app-title");

        Label subtitle = new Label("Autonomous driving with real vehicle physics  \u00B7  v" + VERSION);
        subtitle.getStyleClass().add("app-subtitle");

        HBox roles = new HBox(12, chip("Admin"), chip("Driver"), chip("Maintenance Technician"));
        roles.setAlignment(Pos.CENTER);

        VBox content = new VBox(16, title, subtitle, roles);
        content.setAlignment(Pos.CENTER);

        StackPane root = new StackPane(content);
        root.getStyleClass().add("app-root");

        Scene scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/com/selfdriving/ui/theme.css"),
                        "theme.css missing from resources").toExternalForm());

        stage.setTitle(APP_NAME);
        stage.setMinWidth(1280);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();
    }

    private static Label chip(String text) {
        Label chip = new Label(text);
        chip.getStyleClass().add("chip");
        return chip;
    }
}
