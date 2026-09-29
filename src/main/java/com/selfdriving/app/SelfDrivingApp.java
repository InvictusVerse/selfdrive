package com.selfdriving.app;

import java.util.Objects;

import javafx.application.Application;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SimulationLoop;
import com.selfdriving.ui.driver.DriverScreen;
import com.selfdriving.world.World;

/**
 * JavaFX application bootstrap: builds the world, starts the simulation thread and opens the
 * driver display.
 */
public final class SelfDrivingApp extends Application {

    public static final String APP_NAME = "Self-Driving Car Control System";
    public static final String VERSION = "0.2.0";

    /** Preferred window content size, in logical (scaled) pixels. */
    private static final double PREFERRED_WIDTH = 1600;
    private static final double PREFERRED_HEIGHT = 900;

    /** Smallest size the layout is designed for. */
    private static final double MIN_WIDTH = 1280;
    private static final double MIN_HEIGHT = 720;

    /** Room for the window's title bar and borders. */
    private static final double FRAME_WIDTH = 16;
    private static final double FRAME_HEIGHT = 40;

    private SimulationLoop loop;
    private DriverScreen screen;

    @Override
    public void start(Stage stage) {
        World world = new World();
        Simulation simulation = new Simulation(VehicleParams.electricSedan(), world);
        loop = new SimulationLoop(simulation);
        screen = new DriverScreen(simulation, world);

        // Visual bounds = the screen minus the taskbar, in the same scaled pixels JavaFX uses,
        // so Windows display scaling (125 %, 150 %...) is already taken into account.
        Rectangle2D desktop = Screen.getPrimary().getVisualBounds();
        boolean fits = PREFERRED_WIDTH + FRAME_WIDTH <= desktop.getWidth()
                && PREFERRED_HEIGHT + FRAME_HEIGHT <= desktop.getHeight();
        double width = fits ? PREFERRED_WIDTH : desktop.getWidth() - FRAME_WIDTH;
        double height = fits ? PREFERRED_HEIGHT : desktop.getHeight() - FRAME_HEIGHT;

        Scene scene = new Scene(screen.node(), width, height);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/com/selfdriving/ui/theme.css"),
                        "theme.css missing from resources").toExternalForm());
        screen.install(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.F11) {
                stage.setFullScreen(!stage.isFullScreen());
                event.consume();
            }
        });

        stage.setTitle(APP_NAME + "  \u00B7  v" + VERSION);
        stage.setMinWidth(Math.min(MIN_WIDTH, desktop.getWidth()));
        stage.setMinHeight(Math.min(MIN_HEIGHT, desktop.getHeight()));
        stage.setFullScreenExitHint("Press F11 or Esc to leave full screen");
        stage.setScene(scene);
        // Screens smaller than the preferred size (e.g. 1920x1080 at 125 % scaling = 1536x864)
        // start maximised instead of spilling off the edge.
        stage.setMaximized(!fits);
        stage.show();
        if (fits) {
            stage.centerOnScreen();
        }

        loop.start();
        screen.start();
        if (DevAutomation.isEnabled()) {
            DevAutomation.run(scene, simulation, screen);
        }
    }

    @Override
    public void stop() {
        if (screen != null) {
            screen.stop();
        }
        if (loop != null) {
            loop.stop();
        }
    }
}
