package com.selfdriving.simulation;

import java.util.ArrayList;
import java.util.List;

import com.selfdriving.physics.CarBody;
import com.selfdriving.physics.VehicleModel;
import com.selfdriving.sensors.RayCaster;
import com.selfdriving.world.Obstacle;
import com.selfdriving.world.OrientedBox;

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
     * @param obstacle    what was hit
     * @param impactSpeed speed into it, m/s
     */
    public record Impact(Obstacle obstacle, double impactSpeed) {
    }

    /** The car's footprint for its current pose. */
    public static OrientedBox carBox(VehicleModel car) {
        double offset = CarBody.centreOffset();
        double c = Math.cos(car.heading());
        double s = Math.sin(car.heading());
        return new OrientedBox(car.x() + c * offset, car.y() + s * offset, car.heading(),
                CarBody.halfLength(), CarBody.HALF_WIDTH);
    }

    /** Checks the car against every nearby obstacle and resolves overlaps. */
    public List<Impact> resolve(VehicleModel car, List<Obstacle> obstacles) {
        List<Impact> impacts = new ArrayList<>();
        OrientedBox box = carBox(car);
        for (Obstacle o : RayCaster.candidates(box.cx(), box.cy(), box.boundingRadius(), obstacles)) {
            OrientedBox.Contact contact = box.overlap(o.box());
            if (contact == null) {
                continue;
            }
            double impact = car.resolveContact(contact.nx(), contact.ny(), contact.depth(), RESTITUTION);
            impacts.add(new Impact(o, impact));
            box = carBox(car);
        }
        return impacts;
    }
}
