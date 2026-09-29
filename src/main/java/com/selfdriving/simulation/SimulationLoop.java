package com.selfdriving.simulation;

import java.lang.System.Logger.Level;

/**
 * Runs the simulation on its own thread at a fixed 120 Hz.
 *
 * <p>A fixed time step keeps the physics stable and repeatable, whatever the display's frame
 * rate. Each tick is scheduled against the clock: the thread sleeps in 1 ms steps and spins
 * for the last moment, because on Windows short sleeps are only accurate to about 2 ms.
 *
 * <p>Slow motion (time scale below 1) shortens the time step; fast forward runs several ticks
 * per real tick.
 */
public final class SimulationLoop {

    /** Simulation tick rate. */
    public static final int TICKS_PER_SECOND = 120;

    /** Length of one tick, s. */
    public static final double TICK_SECONDS = 1.0 / TICKS_PER_SECOND;

    private static final long TICK_NANOS = 1_000_000_000L / TICKS_PER_SECOND;
    private static final long SPIN_NANOS = 2_000_000L;
    private static final long MAX_LAG_NANOS = 100_000_000L;
    private static final int MAX_TICKS_PER_CYCLE = 8;

    private static final System.Logger LOG = System.getLogger(SimulationLoop.class.getName());

    private final Simulation simulation;
    private volatile boolean running;
    private Thread thread;

    public SimulationLoop(Simulation simulation) {
        this.simulation = simulation;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = Thread.ofPlatform().name("simulation").daemon().start(this::run);
    }

    public synchronized void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    private void run() {
        long next = System.nanoTime();
        double fastForwardDebt = 0;
        while (running) {
            try {
                simulation.processCommands();
                if (!simulation.isPaused()) {
                    double scale = simulation.timeScale();
                    if (scale <= 1.0) {
                        simulation.step(TICK_SECONDS * scale);
                    } else {
                        fastForwardDebt += scale;
                        int ticks = 0;
                        while (fastForwardDebt >= 1 && ticks < MAX_TICKS_PER_CYCLE) {
                            simulation.step(TICK_SECONDS);
                            fastForwardDebt -= 1;
                            ticks++;
                        }
                        fastForwardDebt = Math.min(fastForwardDebt, 1);
                    }
                }
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Simulation step failed; pausing", e);
                simulation.setPaused(true);
            }

            next += TICK_NANOS;
            long now = System.nanoTime();
            if (now - next > MAX_LAG_NANOS) {
                next = now; // fell far behind (debugger, sleep): resynchronise instead of catching up
            }
            if (!waitUntil(next)) {
                return;
            }
        }
    }

    private boolean waitUntil(long deadline) {
        try {
            while (deadline - System.nanoTime() > SPIN_NANOS) {
                Thread.sleep(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return running;
    }
}
