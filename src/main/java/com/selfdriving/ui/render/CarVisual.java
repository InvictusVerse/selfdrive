package com.selfdriving.ui.render;

import javafx.scene.Group;
import javafx.scene.Node;

import com.selfdriving.vehicle.LightState;

/**
 * The look of the car: a body, four wheels and the lamps, in the car's local frame
 * (X forward, Y down, Z left, origin on the ground under the centre of gravity).
 *
 * <p>{@link CarModel} moves the visual, applies suspension motion to the body and spins and
 * steers the wheels, so a visual only has to provide the shapes.
 *
 * @param name          shown in logs and the help panel
 * @param body          everything that moves with the suspension
 * @param wheels        front-left, front-right, rear-left, rear-right, each centred on its hub
 * @param wheelCentres  hub positions {x, y, z} in the car frame, same order
 * @param lamps         switches the lamps
 * @param length        overall length, m
 * @param frontOverhang distance from the origin to the front bumper, m (for the headlight beams)
 * @param lampHeight    headlight height above the ground, m
 * @param lampSpacing   distance between the two headlights, m
 */
public record CarVisual(String name, Group body, Node[] wheels, double[][] wheelCentres, Lamps lamps,
                        double length, double frontOverhang, double lampHeight, double lampSpacing) {

    /** Switches lamps on the model to match the car. */
    @FunctionalInterface
    public interface Lamps {
        void show(LightState lights);
    }
}
