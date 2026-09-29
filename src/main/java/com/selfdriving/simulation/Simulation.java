package com.selfdriving.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.selfdriving.alerts.Alert;
import com.selfdriving.alerts.AlertBus;
import com.selfdriving.autopilot.Autopilot;
import com.selfdriving.autopilot.SafetyController;
import com.selfdriving.navigation.PathFinder;
import com.selfdriving.navigation.RoadGraph;
import com.selfdriving.navigation.Route;
import com.selfdriving.navigation.RouteTracker;
import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;
import com.selfdriving.physics.VehicleInputs;
import com.selfdriving.physics.VehicleModel;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.physics.Wheel;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.sensors.SensorSuite;
import com.selfdriving.vehicle.DriveMode;
import com.selfdriving.vehicle.DriverControls;
import com.selfdriving.vehicle.DriverInput;
import com.selfdriving.vehicle.GearSelector;
import com.selfdriving.vehicle.LightState;
import com.selfdriving.vehicle.Lights;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.Place;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Pose;
import com.selfdriving.world.World;

/**
 * The simulated world for one car: driver controls, gears, sensors, navigation, autopilot,
 * safety controller, physics, collisions, scenarios, alerts and road tests.
 *
 * <p><b>Each tick</b>, in this order:
 * <ol>
 *   <li>smooth the driver's inputs (needed to drive and to detect a take-over),</li>
 *   <li>move scenario actors,</li>
 *   <li>scan with the sensors (every other tick, 60 Hz),</li>
 *   <li>update route progress,</li>
 *   <li>decide who drives: driver, autopilot or emergency stop,</li>
 *   <li>let the safety controller overrule with emergency braking,</li>
 *   <li>run the physics, then resolve collisions,</li>
 *   <li>road tests, alerts, publish a snapshot.</li>
 * </ol>
 *
 * <p><b>Threading:</b> all state is owned by the simulation thread. Other threads interact in
 * three thread-safe ways only:
 * <ul>
 *   <li>{@link #driverInput()}: live key and touch state,</li>
 *   <li>{@link #submit(Consumer)}: commands, run on the simulation thread before the next tick,</li>
 *   <li>{@link #latest()} and {@link #pollNotification()}: read results.</li>
 * </ul>
 */
public final class Simulation {

    /** Consumption assumed before enough distance has been driven to measure it, Wh/km. */
    private static final double DEFAULT_CONSUMPTION = 150;
    private static final double DEFAULT_MAX_AUTOPILOT_SPEED = 100 / 3.6;
    private static final int SENSOR_INTERVAL_TICKS = 2;
    private static final int ALERTS_IN_SNAPSHOT = 12;
    /** The autopilot switches the indicator on this far before a turn, m. */
    private static final double INDICATE_BEFORE_TURN = 45;
    /** Distance past the start of a turn manoeuvre until the car is through the junction, m. */
    private static final double TURN_LENGTH = 20;

    private final VehicleParams params;
    private final World world;
    private final RoadGraph graph;
    private final VehicleModel car;
    private final DriverInput driverInput = new DriverInput();
    private final DriverControls controls;
    private final GearSelector gearSelector = new GearSelector();
    private final PerformanceMonitor monitor;
    private final SensorSuite sensors = new SensorSuite();
    private final Autopilot autopilot;
    private final SafetyController safety = new SafetyController();
    private final CollisionSystem collisions = new CollisionSystem();
    private final ScenarioManager scenarios = new ScenarioManager();
    private final AlertBus alerts = new AlertBus();
    private final Lights lights = new Lights();
    private final Pose start;

    private final Queue<Consumer<Simulation>> commands = new ConcurrentLinkedQueue<>();
    private final Queue<String> notifications = new ConcurrentLinkedQueue<>();
    private final AtomicReference<SimulationSnapshot> latest = new AtomicReference<>();

    private final Set<Integer> closedEdges = new HashSet<>();
    private Surface surface = Surface.DRY;
    private DriveMode mode = DriveMode.MANUAL;
    private RouteTracker tracker;
    private SensorReadings readings = SensorReadings.empty();
    private SafetyController.Assessment assessment = SafetyController.Assessment.clear();
    private Autopilot.Command lastCommand;
    private VehicleInputs applied = VehicleInputs.parked();
    private double maxAutopilotSpeed = DEFAULT_MAX_AUTOPILOT_SPEED;
    private boolean emergencyBrakingEnabled = true;
    private boolean wasWarning;
    private boolean wasBraking;
    private volatile boolean paused;
    private volatile double timeScale = 1.0;
    private long tick;
    private double time;
    private LightState lightState = LightState.off();
    /** Clock time (seconds since midnight) at simulation time zero. */
    private double clockStart = java.time.LocalTime.now().toSecondOfDay();

    public Simulation(VehicleParams params, World world) {
        this(params, world, world.start());
    }

    public Simulation(VehicleParams params, World world, Pose start) {
        this.params = params;
        this.world = world;
        this.graph = RoadGraph.build(world);
        this.car = new VehicleModel(params);
        this.controls = new DriverControls(params);
        this.monitor = new PerformanceMonitor(params);
        this.autopilot = new Autopilot(params);
        this.start = start;
        // Warnings pop up as toasts; critical alerts get their own card on the display until acknowledged.
        alerts.subscribe(alert -> {
            if (alert.severity() == Alert.Severity.WARNING) {
                notifyDriver(alert.message());
            }
        });
        car.reset(start.x(), start.y(), start.heading());
        updateLights(0);
        publish();
    }

    // ---- Thread-safe access -------------------------------------------------------------

    /** Live key and touch state. The UI writes it; the simulation reads it every tick. */
    public DriverInput driverInput() {
        return driverInput;
    }

    /** Queues a command to run on the simulation thread. */
    public void submit(Consumer<Simulation> command) {
        commands.add(command);
    }

    /** Most recent snapshot. */
    public SimulationSnapshot latest() {
        return latest.get();
    }

    /** Next message for the driver, or null. */
    public String pollNotification() {
        return notifications.poll();
    }

    public boolean isPaused() {
        return paused;
    }

    public double timeScale() {
        return timeScale;
    }

    public World world() {
        return world;
    }

    /** The road network (immutable, safe to share). */
    public RoadGraph graph() {
        return graph;
    }

    // ---- Simulation thread --------------------------------------------------------------

    /** Runs queued commands. Called by the loop before each tick, also while paused. */
    public void processCommands() {
        Consumer<Simulation> command;
        boolean changed = false;
        while ((command = commands.poll()) != null) {
            command.accept(this);
            changed = true;
        }
        if (changed) {
            publish();
        }
    }

    /** Advances the world by one tick. */
    public void step(double dt) {
        double speed = car.speed();
        controls.update(driverInput, speed, dt);
        scenarios.update(time, dt);
        List<Obstacle> obstacles = obstacles();

        if (tick % SENSOR_INTERVAL_TICKS == 0) {
            readings = sensors.scan(car.x(), car.y(), car.heading(), car.worldVx(), car.worldVy(), obstacles);
        }
        if (tracker != null) {
            tracker.update(car.x(), car.y());
        }

        VehicleInputs inputs = decideInputs(dt, speed);

        Route safetyRoute = mode == DriveMode.AUTOPILOT && tracker != null ? tracker.route() : null;
        assessment = safety.assess(emergencyBrakingEnabled, car.x(), car.y(), car.heading(), car.forwardSpeed(),
                car.yawRate(), safetyRoute, tracker != null ? tracker.arc() : 0, readings.detected(),
                surface.friction());
        if (assessment.emergencyBraking()) {
            inputs = new VehicleInputs(0, 1, inputs.steerAngle(), inputs.gear(), 1);
        }
        raiseSafetyAlerts();

        applied = inputs;
        car.step(dt, inputs, surface);
        for (CollisionSystem.Impact impact : collisions.resolve(car, obstacles)) {
            if (impact.impactSpeed() > 0.5) {
                alerts.publish(time, Alert.Severity.CRITICAL, Alert.Category.COLLISION,
                        String.format("Collision with %s at %.0f km/h", impact.obstacle().label().toLowerCase(),
                                impact.impactSpeed() * 3.6), "Collision system");
                if (mode == DriveMode.AUTOPILOT) {
                    setMode(DriveMode.MANUAL);
                }
            }
        }
        tick++;
        time += dt;
        updateLights(dt);

        monitor.update(dt, car.forwardSpeed(), car.distance(), inputs.throttle(), inputs.brake(),
                gearSelector.gear(), surface, mode == DriveMode.MANUAL).ifPresent(this::notifyDriver);
        publish();
    }

    private VehicleInputs decideInputs(double dt, double speed) {
        if (mode == DriveMode.AUTOPILOT) {
            if (driverInput.isOverriding()) {
                setMode(DriveMode.MANUAL);
                alerts.publish(time, Alert.Severity.INFO, Alert.Category.AUTOPILOT,
                        "Autopilot off: driver took over", "Autopilot");
                notifyDriver("Autopilot off: you have control");
            } else if (tracker.isAtDestination() && Math.abs(car.forwardSpeed()) < 0.5) {
                arrive();
            } else if (tracker.isOffRoute()) {
                setMode(DriveMode.MANUAL);
                alerts.publish(time, Alert.Severity.WARNING, Alert.Category.NAVIGATION,
                        "Autopilot off: car left the route", "Autopilot");
            } else {
                lastCommand = autopilot.drive(dt, car, tracker, readings.detected(), maxAutopilotSpeed);
                double throttle = Math.max(lastCommand.throttle(), controls.throttle());
                return new VehicleInputs(throttle, lastCommand.brake(), lastCommand.steerAngle(), Gear.DRIVE,
                        throttle > lastCommand.throttle() ? 1 : lastCommand.regen());
            }
        }
        if (mode == DriveMode.EMERGENCY_STOP) {
            if (Math.abs(car.forwardSpeed()) < 0.2) {
                gearSelector.force(Gear.PARK);
                setMode(DriveMode.MANUAL);
                alerts.publish(time, Alert.Severity.WARNING, Alert.Category.BRAKES,
                        "Emergency stop complete: car secured in Park", "Driver");
            }
            return new VehicleInputs(0, 1, controls.steerAngle(speed), gearSelector.gear(), 1);
        }
        return new VehicleInputs(controls.throttle(), controls.brake(), controls.steerAngle(speed),
                gearSelector.gear());
    }

    /** The autopilot indicates before its turns; the lamps follow pedals, gear and daylight. */
    private void updateLights(double dt) {
        Lights.Indicator auto = Lights.Indicator.OFF;
        if (mode == DriveMode.AUTOPILOT && tracker != null) {
            // From shortly before the turn until the car is through it, as a driver would.
            for (Route.Maneuver m : tracker.route().maneuvers()) {
                double ahead = m.arc() - tracker.arc();
                if (ahead < INDICATE_BEFORE_TURN && ahead > -TURN_LENGTH && m.type() != Route.Maneuver.Type.ARRIVE) {
                    auto = m.type() == Route.Maneuver.Type.LEFT ? Lights.Indicator.LEFT : Lights.Indicator.RIGHT;
                    break;
                }
            }
        }
        lights.setAutoIndicator(auto);
        double steer = mode == DriveMode.MANUAL ? controls.steer() : applied.steerAngle() / params.maxSteerAngle();
        lightState = lights.update(dt, steer, applied.brake(), car.accelForward(), car.forwardSpeed(),
                gearSelector.gear(), isDark());
    }

    /** Local clock, seconds since midnight. */
    public double timeOfDay() {
        return ((clockStart + time) % 86_400 + 86_400) % 86_400;
    }

    /** Between sunset and sunrise (typical for India: about 18:30 to 06:15). */
    private boolean isDark() {
        double hours = timeOfDay() / 3600;
        return hours < 6.25 || hours >= 18.5;
    }

    /** A simple daily temperature curve: coolest around 03:00 (22 °C), warmest around 15:00 (33 °C). */
    private double outsideTemperature() {
        double hours = timeOfDay() / 3600;
        return 27.5 + 5.5 * Math.cos(2 * Math.PI * (hours - 15) / 24);
    }

    private void raiseSafetyAlerts() {
        if (assessment.warning() && !wasWarning) {
            alerts.publish(time, Alert.Severity.WARNING, Alert.Category.OBSTACLE,
                    String.format("Collision warning: obstacle %.1f s ahead", assessment.timeToCollision()),
                    "Safety controller");
        }
        if (assessment.emergencyBraking() && !wasBraking) {
            alerts.publish(time, Alert.Severity.CRITICAL, Alert.Category.BRAKES,
                    "Emergency braking: obstacle in the path", "Safety controller");
        }
        wasWarning = assessment.warning();
        wasBraking = assessment.emergencyBraking();
    }

    private void arrive() {
        String destination = tracker.route().destination();
        setMode(DriveMode.MANUAL);
        gearSelector.force(Gear.PARK);
        tracker = null;
        alerts.publish(time, Alert.Severity.INFO, Alert.Category.NAVIGATION, "Arrived at " + destination,
                "Navigation");
        notifyDriver("Arrived at " + destination + ". Car secured in Park");
    }

    private void setMode(DriveMode newMode) {
        mode = newMode;
        if (newMode != DriveMode.AUTOPILOT) {
            lastCommand = null;
        }
    }

    private List<Obstacle> obstacles() {
        List<Obstacle> all = new ArrayList<>(world.buildings());
        all.addAll(scenarios.actors());
        return all;
    }

    // ---- Commands: driving ----------------------------------------------------------------

    /** Gear request from the keyboard: all rules apply, including "brake to leave Park". */
    public void requestGear(Gear gear) {
        if (mode != DriveMode.MANUAL) {
            notifyDriver("Take over first to change gear");
            return;
        }
        GearSelector.Result result = gearSelector.request(gear, car.forwardSpeed(), controls.brake());
        if (!result.accepted()) {
            notifyDriver(result.message());
        }
    }

    /**
     * Gear request from a tap on the screen. A single mouse or finger cannot hold the brake and
     * tap at the same time, so while the car is stopped the car holds itself on the brake for
     * the shift, as touchscreen drive selectors do. Every other rule still applies.
     */
    public void requestGearFromScreen(Gear gear) {
        if (mode != DriveMode.MANUAL) {
            notifyDriver("Take over first to change gear");
            return;
        }
        boolean stopped = Math.abs(car.forwardSpeed()) < GearSelector.PARK_MAX_SPEED;
        double brake = stopped ? 1.0 : controls.brake();
        GearSelector.Result result = gearSelector.request(gear, car.forwardSpeed(), brake);
        if (!result.accepted()) {
            notifyDriver(result.message());
        }
    }

    // ---- Commands: lights ---------------------------------------------------------------------

    /** Indicator lever: the same side again switches it off. */
    public void toggleIndicator(Lights.Indicator side) {
        lights.toggleIndicator(side);
        updateLights(0);
    }

    public void toggleHazard() {
        lights.toggleHazard();
        updateLights(0);
    }

    /** Headlight switch: Off, Auto, On. */
    public void cycleHeadlights() {
        lights.setHeadlightMode(lights.headlightMode().next());
        updateLights(0);
        notifyDriver("Headlights: " + lights.headlightMode().label());
    }

    public void setHeadlightMode(Lights.HeadlightMode headlightMode) {
        lights.setHeadlightMode(headlightMode);
        updateLights(0);
    }

    /** Main beam on or off (the dipper switch). */
    public void toggleMainBeam() {
        lights.toggleMainBeam();
        updateLights(0);
        if (lightState.highBeam()) {
            notifyDriver("Main beam on");
        } else if (!lightState.lowBeam()) {
            notifyDriver("Main beam works with the headlights on");
        }
    }

    /** Flash the main beam while held. */
    public void setHeadlightFlash(boolean on) {
        lights.setFlash(on);
        updateLights(0);
    }

    /** Sets the clock (e.g. to try the headlights at night). */
    public void setTimeOfDay(double secondsSinceMidnight) {
        clockStart = secondsSinceMidnight - time;
        updateLights(0);
    }

    /** Brakes to a stop at once and secures the car in Park (overrules the autopilot). */
    public void emergencyStop() {
        lights.setHazard(true); // warn following traffic, as cars do under emergency braking
        setMode(DriveMode.EMERGENCY_STOP);
        alerts.publish(time, Alert.Severity.CRITICAL, Alert.Category.BRAKES, "Emergency stop requested",
                "Driver");
    }

    // ---- Commands: navigation and autopilot ----------------------------------------------

    /** Plans the fastest route from the car's position to a place. */
    public void setDestination(Place place) {
        Optional<RoadGraph.Location> here = graph.locate(car.x(), car.y(), car.heading());
        if (here.isEmpty()) {
            notifyDriver("Drive onto a road in the direction of travel to plan a route");
            return;
        }
        RoadGraph.Node goal = graph.nodeAt(place.location()).orElseThrow();
        Optional<Route> route = plan(here.get(), goal, place.name());
        if (route.isEmpty()) {
            notifyDriver("No open route to " + place.name());
            return;
        }
        tracker = new RouteTracker(route.get());
        tracker.update(car.x(), car.y());
        notifyDriver(String.format("Route to %s: %.1f km, about %.0f min", place.name(),
                route.get().length() / 1000, Math.ceil(route.get().estimatedSeconds() / 60)));
    }

    private Optional<Route> plan(RoadGraph.Location from, RoadGraph.Node goal, String name) {
        return PathFinder.aStar().find(graph, from.edge(), from.arc(), goal, closedEdges)
                .map(result -> Route.build(result.edges(), from.arc(), name));
    }

    public void clearRoute() {
        if (mode == DriveMode.AUTOPILOT) {
            setMode(DriveMode.MANUAL);
        }
        tracker = null;
    }

    /** Hands driving to the autopilot (needs a route, and the car not in Reverse). */
    public void engageAutopilot() {
        if (tracker == null) {
            notifyDriver("Choose a destination first");
            return;
        }
        if (gearSelector.gear() == Gear.REVERSE) {
            notifyDriver("Autopilot is not available in Reverse");
            return;
        }
        if (!sensors.isLidarWorking() && !sensors.isRadarWorking()) {
            alerts.publish(time, Alert.Severity.WARNING, Alert.Category.SENSOR,
                    "Autopilot unavailable: no forward sensors", "Autopilot");
            return;
        }
        if (tracker.isOffRoute()) {
            notifyDriver("Return to the route to use autopilot");
            return;
        }
        if (gearSelector.gear() != Gear.DRIVE) {
            if (Math.abs(car.forwardSpeed()) > GearSelector.DIRECTION_CHANGE_MAX_SPEED) {
                notifyDriver("Slow down to engage autopilot");
                return;
            }
            gearSelector.force(Gear.DRIVE);
        }
        autopilot.reset(applied.steerAngle());
        setMode(DriveMode.AUTOPILOT);
        alerts.publish(time, Alert.Severity.INFO, Alert.Category.AUTOPILOT,
                "Autopilot on: driving to " + tracker.route().destination(), "Autopilot");
        notifyDriver("Autopilot on. Brake or steer to take over");
    }

    public void disengageAutopilot() {
        if (mode == DriveMode.AUTOPILOT) {
            setMode(DriveMode.MANUAL);
            notifyDriver("Autopilot off: you have control");
        }
    }

    public void setMaxAutopilotSpeed(double metresPerSecond) {
        maxAutopilotSpeed = Math.max(10 / 3.6, Math.min(130 / 3.6, metresPerSecond));
    }

    public void setEmergencyBrakingEnabled(boolean enabled) {
        emergencyBrakingEnabled = enabled;
        if (!enabled) {
            safety.reset();
        }
        alerts.publish(time, enabled ? Alert.Severity.INFO : Alert.Severity.WARNING, Alert.Category.BRAKES,
                enabled ? "Automatic emergency braking on" : "Automatic emergency braking OFF", "Driver");
    }

    public void acknowledgeAlert(long id) {
        alerts.acknowledge(id);
    }

    // ---- Commands: scenarios ----------------------------------------------------------------

    /**
     * A pedestrian steps off the kerb and walks across the car's path, timed so that at the
     * current speed they would meet the car in the middle of the lane.
     */
    public void scenarioPedestrian() {
        double v = Math.max(8, car.forwardSpeed());
        double ahead = Math.max(30, v * ScenarioManager.crossingTime());
        Point2 point;
        double heading;
        if (tracker != null) {
            point = tracker.route().pointAt(tracker.arc() + ahead);
            heading = tracker.route().headingAt(tracker.arc() + ahead);
        } else {
            point = new Point2(car.x() + Math.cos(car.heading()) * ahead, car.y() + Math.sin(car.heading()) * ahead);
            heading = car.heading();
        }
        scenarios.pedestrianCrossing(point, heading);
        notifyDriver("Scenario: a pedestrian is about to cross ahead");
    }

    /** A vehicle stands in the lane ahead, then pulls away after a while. */
    public void scenarioStoppedVehicle() {
        if (tracker == null) {
            notifyDriver("Choose a destination first: the vehicle is placed on your route");
            return;
        }
        double ahead = Math.max(70, car.forwardSpeed() * 5);
        if (tracker.arc() + ahead > tracker.route().length() - 20) {
            notifyDriver("Too close to the destination for this scenario");
            return;
        }
        scenarios.stoppedVehicle(tracker.route(), tracker.arc() + ahead, time);
        notifyDriver("Scenario: a vehicle has stopped in your lane ahead");
    }

    /** Closes the next road on the route with a barrier and re-routes around it. */
    public void scenarioRoadClosed() {
        if (tracker == null) {
            notifyDriver("Choose a destination first: the closure is placed on your route");
            return;
        }
        List<Integer> ids = tracker.route().edgeIds();
        Optional<RoadGraph.Location> here = graph.locate(car.x(), car.y(), car.heading());
        if (here.isEmpty()) {
            notifyDriver("Drive onto the route first");
            return;
        }
        int current = Math.max(0, ids.indexOf(here.get().edge().id()));
        RoadGraph.Node goal = graph.edge(ids.get(ids.size() - 1)).to();
        // Close the first road ahead that has a way around it (the circuit and the connector have none).
        for (int i = current + 1; i < ids.size(); i++) {
            RoadGraph.Edge edge = graph.edge(ids.get(i));
            if (edge.to().id() == goal.id()) {
                break; // never close the destination's own street
            }
            Set<Integer> trial = new HashSet<>(closedEdges);
            trial.add(edge.id());
            if (edge.reverseId() >= 0) {
                trial.add(edge.reverseId());
            }
            boolean detourExists = PathFinder.aStar()
                    .find(graph, here.get().edge(), here.get().arc(), goal, trial).isPresent();
            if (!detourExists) {
                continue;
            }
            closedEdges.clear();
            closedEdges.addAll(trial);
            scenarios.roadBarrier(pointAlong(edge, 12), startHeading(edge), 8);
            alerts.publish(time, Alert.Severity.WARNING, Alert.Category.NAVIGATION,
                    "Road closed ahead: " + edge.roadName(), "Navigation");
            reroute();
            return;
        }
        notifyDriver("No road ahead can be closed without blocking the route (try in the city)");
    }

    /** Re-plans the current route from where the car is, avoiding closed roads. */
    private void reroute() {
        if (tracker == null) {
            return;
        }
        Route old = tracker.route();
        Optional<RoadGraph.Location> here = graph.locate(car.x(), car.y(), car.heading());
        RoadGraph.Node goal = graph.edge(old.edgeIds().get(old.edgeIds().size() - 1)).to();
        Optional<Route> route = here.flatMap(l -> plan(l, goal, old.destination()));
        if (route.isEmpty()) {
            if (mode == DriveMode.AUTOPILOT) {
                setMode(DriveMode.MANUAL);
            }
            tracker = null;
            alerts.publish(time, Alert.Severity.WARNING, Alert.Category.NAVIGATION,
                    "No open route to " + old.destination(), "Navigation");
            return;
        }
        tracker = new RouteTracker(route.get());
        tracker.update(car.x(), car.y());
        alerts.publish(time, Alert.Severity.INFO, Alert.Category.NAVIGATION,
                String.format("New route to %s: %.1f km", old.destination(), route.get().length() / 1000),
                "Navigation");
        notifyDriver(String.format("Re-routed around the closure: %.1f km to %s", route.get().length() / 1000,
                old.destination()));
    }

    /** Removes all scenario actors and re-opens closed roads. */
    public void clearScenarios() {
        scenarios.clear();
        closedEdges.clear();
        safety.reset();
        notifyDriver("Scenarios cleared, all roads open");
    }

    /** A point a distance into a road from its start (roads start straight). */
    private static Point2 pointAlong(RoadGraph.Edge edge, double arc) {
        Point2 a = edge.centre().points().get(0);
        double h = startHeading(edge);
        return new Point2(a.x() + Math.cos(h) * arc, a.y() + Math.sin(h) * arc);
    }

    private static double startHeading(RoadGraph.Edge edge) {
        List<Point2> points = edge.centre().points();
        Point2 a = points.get(0);
        Point2 b = points.get(Math.min(points.size() - 1, 1));
        return Math.atan2(b.y() - a.y(), b.x() - a.x());
    }

    // ---- Commands: car and environment -------------------------------------------------------

    public void setSurface(Surface surface) {
        this.surface = surface;
        notifyDriver("Road surface: " + surface.label() + " (\u03BC = " + surface.friction() + ")");
    }

    public void setAbsEnabled(boolean enabled) {
        car.setAbsEnabled(enabled);
        notifyDriver(enabled ? "ABS on" : "ABS off: wheels can lock");
    }

    public void setTractionControlEnabled(boolean enabled) {
        car.setTractionControlEnabled(enabled);
        notifyDriver(enabled ? "Traction control on" : "Traction control off: wheels can spin");
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public void setTimeScale(double scale) {
        this.timeScale = Math.max(0.1, Math.min(4, scale));
    }

    /** Puts the car back at the start line, stopped, in Park. */
    public void resetCar() {
        car.reset(start.x(), start.y(), start.heading());
        controls.reset();
        gearSelector.force(Gear.PARK);
        monitor.cancel();
        safety.reset();
        setMode(DriveMode.MANUAL);
        tracker = null;
        Lights.HeadlightMode headlightMode = lights.headlightMode();
        lights.reset();
        lights.setHeadlightMode(headlightMode);
        updateLights(0);
        notifyDriver("Car reset to the start line");
    }

    /** Test hook: sets the car rolling at a speed without driving there first. */
    void setSpeedForTest(double speed) {
        car.setForwardSpeed(speed);
    }

    private void notifyDriver(String message) {
        notifications.add(message);
    }

    // ---- Snapshot -----------------------------------------------------------------------

    private void publish() {
        SimulationSnapshot.Navigation navigation = null;
        if (tracker != null) {
            navigation = new SimulationSnapshot.Navigation(tracker.route(), tracker.arc(),
                    tracker.remainingDistance(), tracker.remainingSeconds(), tracker.nextManeuver().instruction(),
                    tracker.distanceToNextManeuver(), tracker.route().speedLimitAt(tracker.arc()),
                    tracker.lateralError());
        }
        SimulationSnapshot.AutopilotStatus autopilotStatus = null;
        if (mode == DriveMode.AUTOPILOT && lastCommand != null) {
            autopilotStatus = new SimulationSnapshot.AutopilotStatus(lastCommand.status(), lastCommand.targetSpeed(),
                    lastCommand.leadObjectId());
        } else if (mode == DriveMode.AUTOPILOT) {
            autopilotStatus = new SimulationSnapshot.AutopilotStatus("Starting", 0, -1);
        }
        Set<Integer> seen = new HashSet<>();
        for (SensorReadings.DetectedObject d : readings.detected()) {
            seen.add(d.id());
        }
        List<SimulationSnapshot.ActorState> actors = new ArrayList<>();
        for (Obstacle o : scenarios.actors()) {
            actors.add(new SimulationSnapshot.ActorState(o.id(), o.kind(), o.box(), o.height(), seen.contains(o.id())));
        }
        latest.set(new SimulationSnapshot(tick, time, vehicleState(), mode, navigation, autopilotStatus, assessment,
                readings, actors, closedEdges, alerts.recent(ALERTS_IN_SNAPSHOT), alerts.unacknowledgedCritical(),
                new SimulationSnapshot.Settings(maxAutopilotSpeed, emergencyBrakingEnabled),
                monitor.lastBrakeTest(), monitor.lastAccelerationTest(), monitor.isAccelerationTestRunning(),
                monitor.accelerationTestTime(), monitor.isBrakeTestRunning(), paused, timeScale, lightState,
                timeOfDay(), outsideTemperature()));
    }

    private VehicleState vehicleState() {
        List<VehicleState.WheelState> wheels = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            Wheel w = car.wheel(i);
            wheels.add(new VehicleState.WheelState(w.steerAngle(), w.rotation(), w.omega(), w.load(),
                    w.slipRatio(), w.slipAngle(), w.forceCarX(), w.forceCarY(), w.gripUsage(),
                    w.absActive(), car.suspension().compression(i)));
        }
        double tripKwh = car.energyUsedJoules() / 3.6e6;
        double km = car.distance() / 1000;
        double consumption = DEFAULT_CONSUMPTION;
        if (km > 0.05) {
            // Trust the measured average more as distance builds up (fully after 10 km).
            double weight = Math.min(1, km / 10);
            consumption = DEFAULT_CONSUMPTION * (1 - weight) + (tripKwh * 1000 / km) * weight;
        }
        consumption = Math.max(60, consumption);
        double batteryKwh = car.battery().remainingKwh();

        double steerInput = mode == DriveMode.MANUAL ? controls.steer() : applied.steerAngle() / params.maxSteerAngle();
        return new VehicleState(
                car.x(), car.y(), car.heading(), car.forwardSpeed(), car.lateralSpeed(), car.yawRate(),
                car.accelForward(), car.accelLeft(), car.sideslipAngle(),
                steerInput, applied.steerAngle(), applied.throttle(), applied.brake(),
                gearSelector.gear(), surface,
                car.isAbsEnabled(), car.isTractionControlEnabled(),
                car.isAbsActive(), car.isTractionControlActive(),
                car.suspension().pitch(), car.suspension().roll(), car.suspension().heave(),
                car.motorTorque(), car.motorRpm(), car.mechanicalPower() / 1000,
                car.electricalPower() / 1000, car.battery().stateOfCharge(), batteryKwh,
                car.distance(), tripKwh, consumption, batteryKwh * 1000 / consumption,
                wheels);
    }

    public VehicleParams params() {
        return params;
    }

    public Pose start() {
        return start;
    }
}
