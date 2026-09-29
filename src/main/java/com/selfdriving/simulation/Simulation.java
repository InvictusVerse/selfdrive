package com.selfdriving.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.Surface;
import com.selfdriving.physics.VehicleInputs;
import com.selfdriving.physics.VehicleModel;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.physics.Wheel;
import com.selfdriving.vehicle.DriverControls;
import com.selfdriving.vehicle.DriverInput;
import com.selfdriving.vehicle.GearSelector;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.Pose;

/**
 * The simulated world for one car: driver controls, gear selector, physics and road tests.
 *
 * <p><b>Threading:</b> all state is owned by the simulation thread. Other threads interact in
 * three thread-safe ways only:
 * <ul>
 *   <li>{@link #driverInput()}: live key state,</li>
 *   <li>{@link #submit(Consumer)}: commands, run on the simulation thread before the next tick,</li>
 *   <li>{@link #latest()} and {@link #pollNotification()}: read results.</li>
 * </ul>
 */
public final class Simulation {

    /** Consumption assumed before enough distance has been driven to measure it, Wh/km. */
    private static final double DEFAULT_CONSUMPTION = 150;

    private final VehicleParams params;
    private final VehicleModel car;
    private final DriverInput driverInput = new DriverInput();
    private final DriverControls controls;
    private final GearSelector gearSelector = new GearSelector();
    private final PerformanceMonitor monitor;
    private final Pose start;

    private final Queue<Consumer<Simulation>> commands = new ConcurrentLinkedQueue<>();
    private final Queue<String> notifications = new ConcurrentLinkedQueue<>();
    private final AtomicReference<SimulationSnapshot> latest = new AtomicReference<>();

    private Surface surface = Surface.DRY;
    private volatile boolean paused;
    private volatile double timeScale = 1.0;
    private long tick;
    private double time;

    public Simulation(VehicleParams params, Pose start) {
        this.params = params;
        this.car = new VehicleModel(params);
        this.controls = new DriverControls(params);
        this.monitor = new PerformanceMonitor(params);
        this.start = start;
        car.reset(start.x(), start.y(), start.heading());
        publish();
    }

    // ---- Thread-safe access -------------------------------------------------------------

    /** Live key state. The UI writes it; the simulation reads it every tick. */
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
        VehicleInputs inputs = new VehicleInputs(
                controls.throttle(), controls.brake(), controls.steerAngle(speed), gearSelector.gear());
        car.step(dt, inputs, surface);
        tick++;
        time += dt;

        monitor.update(dt, car.forwardSpeed(), car.distance(), controls.throttle(), controls.brake(),
                gearSelector.gear(), surface).ifPresent(this::notifyDriver);
        publish();
    }

    // ---- Commands (run via submit) ------------------------------------------------------

    public void requestGear(Gear gear) {
        GearSelector.Result result = gearSelector.request(gear, car.forwardSpeed(), controls.brake());
        if (!result.accepted()) {
            notifyDriver(result.message());
        }
    }

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
        notifyDriver("Car reset to the start line");
    }

    private void notifyDriver(String message) {
        notifications.add(message);
    }

    // ---- Snapshot -----------------------------------------------------------------------

    private void publish() {
        latest.set(new SimulationSnapshot(tick, time, vehicleState(), monitor.lastBrakeTest(),
                monitor.lastAccelerationTest(), monitor.isAccelerationTestRunning(),
                monitor.accelerationTestTime(), monitor.isBrakeTestRunning(), paused, timeScale));
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

        double speed = car.speed();
        return new VehicleState(
                car.x(), car.y(), car.heading(), car.forwardSpeed(), car.lateralSpeed(), car.yawRate(),
                car.accelForward(), car.accelLeft(), car.sideslipAngle(),
                controls.steer(), controls.steerAngle(speed), controls.throttle(), controls.brake(),
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
