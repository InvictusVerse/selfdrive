package com.selfdriving.simulation;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.VehicleModel;
import com.selfdriving.sensors.RayCaster;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Walls;

/**
 * Detects the car touching anything solid and resolves it: the car is pushed out and loses the
 * speed that went into the object (a low bounce, as with crumpling bodywork).
 *
 * <p>The car's footprint is an oriented box around the body; other objects are their boxes.
 */
public final class CollisionSystem {

    private static final double RESTITUTION = 0.15;

    /**
     * A collision in this step.
     *
     * @param obstacle    what was hit, or null for a building
     * @param what        short description ("building", "car", ...)
     * @param impactSpeed speed into it, m/s
     */
    public record Impact(Obstacle obstacle, String what, double impactSpeed) {
    }

    /** The car's footprint for its current pose. */
    public static OrientedBox carBox(VehicleModel car) {
        double offset = CarBody.centreOffset();
        double c = Math.cos(car.heading());
        double s = Math.sin(car.heading());
        return new OrientedBox(car.x() + c * offset, car.y() + s * offset, car.heading(),
                CarBody.halfLength(), CarBody.HALF_WIDTH);
    }

    /** Checks the car against every nearby obstacle and building wall and resolves overlaps. */
    public List<Impact> resolve(VehicleModel car, List<Obstacle> obstacles, Walls walls) {
        List<Impact> impacts = new ArrayList<>();
        OrientedBox box = carBox(car);
        for (Obstacle o : RayCaster.candidates(box.cx(), box.cy(), box.boundingRadius(), obstacles)) {
            OrientedBox.Contact contact = box.overlap(o.box());
            if (contact == null) {
                continue;
            }
            double impact = car.resolveContact(contact.nx(), contact.ny(), contact.depth(), RESTITUTION);
            impacts.add(new Impact(o, o.label().toLowerCase(java.util.Locale.ROOT), impact));
            box = carBox(car);
        }
        if (walls != null) {
            double strongest = 0;
            for (int i = 0; i < 3; i++) { // a corner can touch two walls
                OrientedBox.Contact contact = walls.contact(box, null);
                if (contact == null) {
                    break;
                }
                strongest = Math.max(strongest, car.resolveContact(contact.nx(), contact.ny(), contact.depth(),
                        RESTITUTION));
                box = carBox(car);
            }
            if (strongest > 0) {
                impacts.add(new Impact(null, "a building", strongest));
            }
        }
        return impacts;
    }

    /** Obstacles only (no buildings). */
    public List<Impact> resolve(VehicleModel car, List<Obstacle> obstacles) {
        return resolve(car, obstacles, null);
    }
}
