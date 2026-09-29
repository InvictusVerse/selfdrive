package com.selfdriving.app;

import java.util.Objects;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SimulationLoop;
import com.selfdriving.ui.driver.DriverScreen;
import com.selfdriving.world.ProvingGround;

/**
 * JavaFX application bootstrap: builds the world, starts the simulation thread and opens the
 * driver display.
 */
public final class SelfDrivingApp extends Application {

    public static final String APP_NAME = "Self-Driving Car Control System";
    public static final String VERSION = "0.2.0";

    private static final double WINDOW_WIDTH = 1600;
    private static final double WINDOW_HEIGHT = 900;

    private SimulationLoop loop;
    private DriverScreen screen;

    @Override
    public void start(Stage stage) {
        ProvingGround ground = new ProvingGround();
        Simulation simulation = new Simulation(VehicleParams.electricSedan(), ground.start());
        loop = new SimulationLoop(simulation);
        screen = new DriverScreen(simulation, ground);

        Scene scene = new Scene(screen.node(), WINDOW_WIDTH, WINDOW_HEIGHT);
        scene.getStylesheets().add(
                Objects.requireNonNull(getClass().getResource("/com/selfdriving/ui/theme.css"),
                        "theme.css missing from resources").toExternalForm());
        screen.install(scene);

        stage.setTitle(APP_NAME + "  \u00B7  v" + VERSION);
        stage.setMinWidth(1280);
        stage.setMinHeight(720);
        stage.setScene(scene);
        stage.show();

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
