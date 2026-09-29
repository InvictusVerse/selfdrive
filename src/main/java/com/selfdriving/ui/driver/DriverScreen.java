package com.selfdriving.ui.driver;

import java.util.EnumSet;
import java.util.Set;

import javafx.animation.AnimationTimer;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;
import com.selfdriving.simulation.Simulation;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.ui.render.CameraRig;
import com.selfdriving.ui.render.DrivingView;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.vehicle.DriverInput;
import com.selfdriving.vehicle.Lights;
import com.selfdriving.world.Place;
import com.selfdriving.world.World;

/**
 * The driver's display, laid out like an electric car's centre screen: the live 3D view with
 * speed and status on the left; map and physics telemetry on the right; controls along the
 * bottom. Reads the simulation's latest snapshot every frame and sends key presses and button
 * commands back to it.
 */
public final class DriverScreen implements ControlDock.Actions, NavigationPanel.Actions, HudOverlay.LightControls {

    private static final double SIDE_PANEL_WIDTH = 640;
    private static final double SLOW_MOTION_SCALE = 0.25;

    private final Simulation simulation;
    private final DriverInput input;
    private final BorderPane root = new BorderPane();
    private final DrivingView drivingView;
    private final HudOverlay hud = new HudOverlay(this::selectGear, this::acknowledgeAlert, this);
    private final NavigationPanel navigation;
    private final TouchControls touch;
    private final Toast toast = new Toast();
    private final HelpOverlay help = new HelpOverlay();
    private final MapView map;
    private final FrictionCircleView frictionCircle = new FrictionCircleView(170);
    private final TyreView tyres = new TyreView(170, 170);
    private final EnergyPanel energy = new EnergyPanel(180);
    private final ControlDock dock = new ControlDock(this);
    private final Set<KeyCode> heldKeys = EnumSet.noneOf(KeyCode.class);
    private final AnimationTimer frameTimer;

    public DriverScreen(Simulation simulation, World ground) {
        this.simulation = simulation;
        this.input = simulation.driverInput();
        this.drivingView = new DrivingView(ground, simulation.params());
        this.map = new MapView(ground, simulation.graph());
        this.touch = new TouchControls(input);
        this.navigation = new NavigationPanel(ground.places(), this);

        StackPane viewStack = new StackPane(drivingView.node(), touch.node(), hud.node(), toast.node(),
                help.node());
        viewStack.setMinSize(0, 0);
        HBox.setHgrow(viewStack, Priority.ALWAYS);

        VBox mapCard = card(null, map.node());
        mapCard.getStyleClass().add("map-card");
        VBox.setVgrow(map.node(), Priority.ALWAYS);
        VBox.setVgrow(mapCard, Priority.ALWAYS);

        HBox telemetry = new HBox(12,
                card("GRIP  \u00B7  g-g", frictionCircle.node()),
                card("TYRES", tyres.node()),
                card("ENERGY", energy.node()));
        VBox side = new VBox(navigation.node(), mapCard, telemetry);
        side.getStyleClass().add("side-panel");
        side.setPrefWidth(SIDE_PANEL_WIDTH);
        side.setMinWidth(SIDE_PANEL_WIDTH);

        root.setCenter(new HBox(viewStack, side));
        root.setBottom(dock.node());

        frameTimer = new AnimationTimer() {
            private long last;

            @Override
            public void handle(long now) {
                double dt = last == 0 ? 1.0 / 60 : Math.min(0.1, (now - last) / 1e9);
                last = now;
                render(dt);
            }
        };
    }

    public Parent node() {
        return root;
    }

    public DrivingView drivingView() {
        return drivingView;
    }

    /** Connects keyboard handling to the window's scene. */
    public void install(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
        scene.addEventFilter(KeyEvent.KEY_RELEASED, this::onKeyReleased);
        scene.windowProperty().addListener((obs, oldWindow, window) -> {
            if (window != null) {
                window.focusedProperty().addListener((o, was, focused) -> {
                    if (!focused) {
                        input.releaseAll();
                        heldKeys.clear();
                    }
                });
            }
        });
    }

    public void start() {
        frameTimer.start();
        drivingView.loadCarModel(simulation.params(), name -> toast.show("Car model: " + name));
    }

    public void stop() {
        frameTimer.stop();
    }

    private void render(double dt) {
        SimulationSnapshot snapshot = simulation.latest();
        drivingView.update(snapshot, dt);
        CameraRig.Mode mode = drivingView.cameraRig().mode();
        hud.update(snapshot, mode);
        touch.update(snapshot.vehicle());
        map.draw(snapshot);
        navigation.update(snapshot);
        frictionCircle.draw(snapshot.vehicle());
        tyres.draw(snapshot.vehicle());
        energy.update(snapshot);
        dock.update(snapshot, mode, drivingView.car().forcesVisible(), touch.isVisible());

        String message;
        while ((message = simulation.pollNotification()) != null) {
            toast.show(message);
        }
    }

    // ---- Keyboard ----------------------------------------------------------------------

    private void onKeyPressed(KeyEvent event) {
        KeyCode code = event.getCode();
        boolean firstPress = heldKeys.add(code);
        if (setDrivingKey(code, true)) {
            help.setVisible(false);
            event.consume();
            return;
        }
        if (!firstPress) {
            event.consume();
            return;
        }
        boolean handled = true;
        switch (code) {
            case DIGIT1, NUMPAD1 -> selectGearByKey(Gear.PARK);
            case DIGIT2, NUMPAD2 -> selectGearByKey(Gear.REVERSE);
            case DIGIT3, NUMPAD3 -> selectGearByKey(Gear.NEUTRAL);
            case DIGIT4, NUMPAD4 -> selectGearByKey(Gear.DRIVE);
            case O -> setTouchControls(!touch.isVisible());
            case G -> cycleSurface();
            case B -> setAbs(!simulation.latest().vehicle().absEnabled());
            case T -> setTractionControl(!simulation.latest().vehicle().tractionEnabled());
            case C -> cycleCamera();
            case F -> setForces(!drivingView.car().forcesVisible());
            case M -> setSlowMotion(simulation.latest().timeScale() > 0.99);
            case P -> setPaused(!simulation.latest().paused());
            case BACK_SPACE -> resetCar();
            case H, F1 -> toggleHelp();
            case E -> toggleAutopilot();
            case X -> emergencyStop();
            case L -> {
                drivingView.setLidarVisible(!drivingView.lidarVisible());
                toast.show(drivingView.lidarVisible() ? "Lidar points on" : "Lidar points off");
            }
            case COMMA -> toggleIndicator(Lights.Indicator.LEFT);
            case PERIOD -> toggleIndicator(Lights.Indicator.RIGHT);
            case SLASH -> toggleHazard();
            case N -> cycleHeadlights();
            case K -> toggleMainBeam();
            case Y -> cycleTraffic();
            case J -> simulation.submit(sim -> sim.setHeadlightFlash(true));
            default -> handled = false;
        }
        if (handled) {
            event.consume();
        }
    }

    private void onKeyReleased(KeyEvent event) {
        heldKeys.remove(event.getCode());
        if (event.getCode() == KeyCode.J) {
            simulation.submit(sim -> sim.setHeadlightFlash(false));
            event.consume();
            return;
        }
        if (setDrivingKey(event.getCode(), false)) {
            event.consume();
        }
    }

    /** Updates the held-key state for driving keys. Returns false for other keys. */
    private boolean setDrivingKey(KeyCode code, boolean held) {
        switch (code) {
            case W, UP -> input.setAccelerate(held);
            case S, DOWN -> input.setBrake(held);
            case A, LEFT -> input.setSteerLeft(held);
            case D, RIGHT -> input.setSteerRight(held);
            case SPACE -> input.setFullBrake(held);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void cycleSurface() {
        Surface[] all = Surface.values();
        Surface current = simulation.latest().vehicle().surface();
        selectSurface(all[(current.ordinal() + 1) % all.length]);
    }

    // ---- Actions (dock buttons and shortcuts) ------------------------------------------------

    /** Keyboard shift: the brake must be held to leave Park, as in a real car. */
    private void selectGearByKey(Gear gear) {
        simulation.submit(sim -> sim.requestGear(gear));
    }

    /** Tap or click on a drive selector (bottom bar or the P R N D letters on the display). */
    @Override
    public void selectGear(Gear gear) {
        simulation.submit(sim -> sim.requestGearFromScreen(gear));
    }

    @Override
    public void setTouchControls(boolean visible) {
        touch.setVisible(visible);
    }

    @Override
    public void selectSurface(Surface surface) {
        simulation.submit(sim -> sim.setSurface(surface));
    }

    @Override
    public void setAbs(boolean enabled) {
        simulation.submit(sim -> sim.setAbsEnabled(enabled));
    }

    @Override
    public void setTractionControl(boolean enabled) {
        simulation.submit(sim -> sim.setTractionControlEnabled(enabled));
    }

    @Override
    public void cycleCamera() {
        CameraRig.Mode mode = drivingView.cameraRig().cycle();
        toast.show("View: " + mode.label());
    }

    @Override
    public void setForces(boolean visible) {
        drivingView.car().setForcesVisible(visible);
    }

    @Override
    public void setSlowMotion(boolean slow) {
        simulation.submit(sim -> sim.setTimeScale(slow ? SLOW_MOTION_SCALE : 1.0));
    }

    @Override
    public void setPaused(boolean paused) {
        simulation.submit(sim -> sim.setPaused(paused));
    }

    @Override
    public void resetCar() {
        simulation.submit(Simulation::resetCar);
        map.clearTrail();
        drivingView.cameraRig().snap();
    }

    @Override
    public void toggleHelp() {
        help.setVisible(!help.isVisible());
    }

    public void setHelpVisible(boolean visible) {
        help.setVisible(visible);
    }

    // ---- Actions (navigation card and HUD) -----------------------------------------------------

    @Override
    public void planRoute(Place destination) {
        simulation.submit(sim -> sim.setDestination(destination));
    }

    @Override
    public void clearRoute() {
        simulation.submit(Simulation::clearRoute);
    }

    @Override
    public void toggleAutopilot() {
        if (simulation.latest().mode() == DriveMode.AUTOPILOT) {
            simulation.submit(Simulation::disengageAutopilot);
        } else {
            simulation.submit(Simulation::engageAutopilot);
        }
    }

    @Override
    public void emergencyStop() {
        simulation.submit(Simulation::emergencyStop);
    }

    @Override
    public void changeMaxSpeed(double deltaKmh) {
        double current = simulation.latest().settings().maxAutopilotSpeed();
        simulation.submit(sim -> sim.setMaxAutopilotSpeed(current + deltaKmh / 3.6));
    }

    @Override
    public void setEmergencyBraking(boolean enabled) {
        simulation.submit(sim -> sim.setEmergencyBrakingEnabled(enabled));
    }

    @Override
    public void scenarioPedestrian() {
        simulation.submit(Simulation::scenarioPedestrian);
    }

    @Override
    public void scenarioStoppedVehicle() {
        simulation.submit(Simulation::scenarioStoppedVehicle);
    }

    @Override
    public void scenarioSlowVehicle() {
        simulation.submit(Simulation::scenarioSlowVehicle);
    }

    @Override
    public void scenarioRoadClosed() {
        simulation.submit(Simulation::scenarioRoadClosed);
    }

    @Override
    public void clearScenarios() {
        simulation.submit(Simulation::clearScenarios);
    }

    private static final int[] TRAFFIC_LEVELS = {0, 80, 160, 240};

    @Override
    public void cycleTraffic() {
        int current = simulation.latest().settings().trafficCount();
        int next = TRAFFIC_LEVELS[0];
        for (int level : TRAFFIC_LEVELS) {
            if (level > current) {
                next = level;
                break;
            }
        }
        int count = next;
        simulation.submit(sim -> sim.setTrafficCount(count));
        toast.show("Traffic: " + NavigationPanel.trafficLevel(count));
    }

    // ---- Actions (lights) ------------------------------------------------------------------

    @Override
    public void toggleIndicator(Lights.Indicator side) {
        simulation.submit(sim -> sim.toggleIndicator(side));
    }

    @Override
    public void toggleHazard() {
        simulation.submit(Simulation::toggleHazard);
    }

    @Override
    public void cycleHeadlights() {
        simulation.submit(Simulation::cycleHeadlights);
    }

    @Override
    public void toggleMainBeam() {
        simulation.submit(Simulation::toggleMainBeam);
    }

    /** Jumps the clock to midday or to the evening, to see the headlights at work. */
    @Override
    public void toggleNight() {
        boolean dark = simulation.latest().lights().dark();
        simulation.submit(sim -> sim.setTimeOfDay((dark ? 12 : 21) * 3600.0));
        toast.show(dark ? "Clock set to 12:00" : "Clock set to 21:00");
    }

    private void acknowledgeAlert(long id) {
        simulation.submit(sim -> sim.acknowledgeAlert(id));
    }

    private static VBox card(String title, Node content) {
        VBox card = new VBox();
        card.getStyleClass().add("card");
        if (title != null) {
            Label label = new Label(title);
            label.getStyleClass().add("card-title");
            card.getChildren().add(label);
        }
        card.getChildren().add(content);
        return card;
    }
}
