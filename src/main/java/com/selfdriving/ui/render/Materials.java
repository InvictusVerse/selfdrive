package com.selfdriving.ui.render;

import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;

/** Colour palette for the 3D scene: a clean, minimal "autopilot display" look. */
final class Materials {

    static final Color BACKGROUND = Color.web("#0b0c0e");

    private Materials() {
    }

    static PhongMaterial matte(String colour) {
        PhongMaterial m = new PhongMaterial(Color.web(colour));
        m.setSpecularColor(Color.web("#1a1a1a"));
        return m;
    }

    static PhongMaterial glossy(String colour, String specular, double power) {
        PhongMaterial m = new PhongMaterial(Color.web(colour));
        m.setSpecularColor(Color.web(specular));
        m.setSpecularPower(power);
        return m;
    }

    /** A material that glows in its own colour regardless of lighting. */
    static PhongMaterial glowing(Color colour) {
        PhongMaterial m = new PhongMaterial(colour.darker());
        m.setSelfIlluminationMap(solid(colour));
        return m;
    }

    private static WritableImage solid(Color colour) {
        WritableImage image = new WritableImage(1, 1);
        image.getPixelWriter().setColor(0, 0, colour);
        return image;
    }
}
