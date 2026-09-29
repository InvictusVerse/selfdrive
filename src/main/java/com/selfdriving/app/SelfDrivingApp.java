package com.selfdriving.app;

import java.lang.System.Logger.Level;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Screen;
import javafx.stage.Stage;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.service.ApplicationContext;
import com.selfdriving.service.Session;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SimulationLoop;
import com.selfdriving.ui.AppShell;
import com.selfdriving.ui.common.Ui;
import com.selfdriving.ui.driver.DriverScreen;
import com.selfdriving.ui.login.LoginScreen;
import com.selfdriving.world.World;

/**
 * JavaFX application bootstrap: builds the world, starts the simulation thread, opens the
 * database and shows the sign-in screen; after sign-in, the pages for the user's role.
 */
public final class SelfDrivingApp extends Application {

    public static final String APP_NAME = "Self-Driving Car Control System";
    public static final String VERSION = "0.4.0";

    private static final System.Logger LOG = System.getLogger(SelfDrivingApp.class.getName());

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
    private ApplicationContext context;
    private LoginScreen login;
    private AppShell shell;
    private Scene scene;

    @Override
    public void start(Stage stage) {
        World world = new World();
        Simulation simulation = new Simulation(VehicleParams.electricSedan(), world);
        try {
            context = ApplicationContext.open(simulation);
        } catch (RuntimeException e) {
            LOG.log(Level.ERROR, "Cannot open the database", e);
            showStartupError(stage, e);
            return;
        }
        loop = new SimulationLoop(simulation);
        screen = new DriverScreen(simulation, world);
        login = new LoginScreen(context, APP_NAME, VERSION, this::signedIn);

        // Visual bounds = the screen minus the taskbar, in the same scaled pixels JavaFX uses,
        // so Windows display scaling (125 %, 150 %...) is already taken into account.
        Rectangle2D desktop = Screen.getPrimary().getVisualBounds();
        boolean fits = PREFERRED_WIDTH + FRAME_WIDTH <= desktop.getWidth()
                && PREFERRED_HEIGHT + FRAME_HEIGHT <= desktop.getHeight();
        double width = fits ? PREFERRED_WIDTH : desktop.getWidth() - FRAME_WIDTH;
        double height = fits ? PREFERRED_HEIGHT : desktop.getHeight() - FRAME_HEIGHT;

        scene = new Scene(login.node(), width, height);
        scene.getStylesheets().add(Ui.THEME);
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
        login.reset(null);
        if (DevAutomation.isEnabled()) {
            DevAutomation.run(scene, simulation, screen, this);
        }
    }

    private void signedIn(Session session) {
        shell = new AppShell(context, session, screen, this::signOut);
        scene.setRoot(shell.node());
    }

    void signOut() {
        if (shell != null) {
            shell.dispose();
            shell = null;
        }
        context.logout();
        scene.setRoot(login.node());
        login.reset("Signed out");
    }

    // ---- For developer scripts --------------------------------------------------------------

    LoginScreen login() {
        return login;
    }

    AppShell shell() {
        return shell;
    }

    private static void showStartupError(Stage stage, RuntimeException e) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR,
                "The database could not be opened: " + e.getMessage()
                        + "\n\nIs the app already running? Only one copy can use the data folder at a time.");
        alert.setTitle(APP_NAME);
        alert.setHeaderText("Cannot start");
        alert.showAndWait();
        Platform.exit();
    }

    @Override
    public void stop() {
        if (shell != null) {
            shell.dispose();
            context.logout();
        }
        if (screen != null) {
            screen.stop();
        }
        if (loop != null) {
            loop.stop();
        }
        if (context != null) {
            context.close();
        }
    }
}
