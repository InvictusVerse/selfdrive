package com.selfdriving.ui.render;

import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.DirectionalLight;
import javafx.scene.Group;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.World;

/**
 * The live 3D view: world, car, lights and camera in a resizable panel.
 *
 * <p>The car starts with the built-in model. If a model file is installed (see
 * {@link #MODEL_PROPERTY}), it is loaded in the background and swapped in when ready.
 */
public final class DrivingView {

    /** System property: a car model file, or a folder to take the first model file from. */
    public static final String MODEL_PROPERTY = "selfdrive.carModel";

    /** Where car model files are looked for by default (not part of the repository). */
    public static final Path DEFAULT_MODEL_FOLDER = Path.of("assets", "models", "car");

    private static final System.Logger LOG = System.getLogger(DrivingView.class.getName());

    private final Pane container = new Pane();
    private final SubScene subScene;
    private final CarModel car;
    private final CameraRig cameraRig = new CameraRig();
    private final DynamicLayer dynamic = new DynamicLayer();
    private final SignalLayer signals;
    private final AmbientLight ambient;
    private final DirectionalLight sun;
    private Boolean night;

    private static final Color DAY_AMBIENT = Color.web("#6d737c");
    private static final Color DAY_SUN = Color.web("#d9dee6");
    private static final Color NIGHT_AMBIENT = Color.web("#23262c");
    private static final Color NIGHT_MOON = Color.web("#2c3340");

    public DrivingView(World ground, VehicleParams params) {
        car = new CarModel(params);
        WorldModel world = new WorldModel(ground);
        signals = new SignalLayer(ground.network());

        ambient = new AmbientLight(DAY_AMBIENT);
        sun = new DirectionalLight(DAY_SUN);
        sun.setDirection(new Point3D(-0.35, 1, 0.45));

        Group root = new Group(world.node(), signals.node(), dynamic.node(), car.node(), ambient, sun);
        subScene = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Materials.BACKGROUND);
        subScene.setCamera(cameraRig.camera());
        subScene.widthProperty().bind(container.widthProperty());
        subScene.heightProperty().bind(container.heightProperty());
        container.getChildren().add(subScene);
        container.setMinSize(0, 0);
    }

    /**
     * Loads the installed car model file, if any, on a background thread.
     *
     * @param onLoaded called on the JavaFX thread with the model's name once it is shown
     */
    public void loadCarModel(VehicleParams params, Consumer<String> onLoaded) {
        Path file = modelFile();
        if (file == null) {
            return;
        }
        Thread loader = new Thread(() -> {
            long start = System.nanoTime();
            try {
                CarVisual visual = ImportedCar.load(file, params);
                LOG.log(Level.INFO, "Car model {0} loaded in {1} ms", file.getFileName(),
                        (System.nanoTime() - start) / 1_000_000);
                Platform.runLater(() -> {
                    car.install(visual);
                    onLoaded.accept(visual.name());
                });
            } catch (Exception | OutOfMemoryError e) {
                LOG.log(Level.WARNING, "Could not load car model " + file + "; using the built-in car", e);
            }
        }, "car-model-loader");
        loader.setDaemon(true);
        loader.setPriority(Thread.MIN_PRIORITY);
        loader.start();
    }

    private static Path modelFile() {
        String configured = System.getProperty(MODEL_PROPERTY);
        Path location = configured == null || configured.isBlank() ? DEFAULT_MODEL_FOLDER : Path.of(configured);
        if (Files.isRegularFile(location)) {
            return location;
        }
        List<Path> found = ImportedCar.findModels(location);
        return found.isEmpty() ? null : found.get(0);
    }

    /** The panel to place in a layout. */
    public Pane node() {
        return container;
    }

    public CameraRig cameraRig() {
        return cameraRig;
    }

    public CarModel car() {
        return car;
    }

    /** Draws a new frame. */
    public void update(SimulationSnapshot snapshot, double dt) {
        VehicleState state = snapshot.vehicle();
        car.update(state, snapshot.lights());
        boolean dark = snapshot.lights().dark();
        if (night == null || night != dark) {
            night = dark;
            ambient.setColor(dark ? NIGHT_AMBIENT : DAY_AMBIENT);
            sun.setColor(dark ? NIGHT_MOON : DAY_SUN);
        }
        dynamic.update(snapshot);
        signals.update(snapshot.time());
        cameraRig.update(state, dt);
    }

    /** Shows or hides the lidar returns. */
    public void setLidarVisible(boolean visible) {
        dynamic.setLidarVisible(visible);
    }

    public boolean lidarVisible() {
        return dynamic.lidarVisible();
    }
}
