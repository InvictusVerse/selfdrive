package com.selfdriving.world;

/**
 * Something the car can see and hit: a building, another car, a pedestrian or a barrier.
 *
 * <p>Owned by the simulation thread. Moving obstacles are updated by their {@link Behaviour}
 * once per tick; other threads only ever see copies in snapshots.
 */
public final class Obstacle {

    /** What an obstacle is. Sensors report it; the display draws it accordingly. */
    public enum Kind {
        BUILDING, CAR, PEDESTRIAN, BARRIER
    }

    /** Moves an obstacle over time (e.g. a pedestrian crossing, a car pulling away). */
    @FunctionalInterface
    public interface Behaviour {
        /**
         * @param obstacle the obstacle to move
         * @param time     simulation time, s
         * @param dt       time step, s
         * @return false when the obstacle should be removed from the world
         */
        boolean update(Obstacle obstacle, double time, double dt);
    }

    private final int id;
    private final Kind kind;
    private final double height;
    private final String label;
    private OrientedBox box;
    private double vx;
    private double vy;
    private Behaviour behaviour;

    public Obstacle(int id, Kind kind, OrientedBox box, double height, String label) {
        this.id = id;
        this.kind = kind;
        this.box = box;
        this.height = height;
        this.label = label;
    }

    public int id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public OrientedBox box() {
        return box;
    }

    /** Height for drawing and for deciding what the lidar can see over, m. */
    public double height() {
        return height;
    }

    public String label() {
        return label;
    }

    /** Velocity east, m/s. */
    public double vx() {
        return vx;
    }

    /** Velocity north, m/s. */
    public double vy() {
        return vy;
    }

    public boolean isStatic() {
        return behaviour == null && vx == 0 && vy == 0;
    }

    public Behaviour behaviour() {
        return behaviour;
    }

    public void setBehaviour(Behaviour behaviour) {
        this.behaviour = behaviour;
    }

    /** Moves the obstacle and records its velocity (used by radar for closing speed). */
    public void moveTo(double x, double y, double heading, double dt) {
        if (dt > 0) {
            vx = (x - box.cx()) / dt;
            vy = (y - box.cy()) / dt;
        }
        box = box.movedTo(x, y, heading);
    }

    /** Stops the obstacle where it is. */
    public void stop() {
        vx = vy = 0;
    }
}
