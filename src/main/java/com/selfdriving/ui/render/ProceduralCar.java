package com.selfdriving.ui.render;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.vehicle.LightState;

/**
 * The car built entirely in code: a lofted body shaped by smooth profiles, a dark glass cabin,
 * five-spoke wheels and simple lamps. Used when no model file is installed.
 */
final class ProceduralCar {

    private static final double VISUAL_HALF_TRACK = 0.83;
    private static final double TYRE_WIDTH = 0.235;
    private static final double RIM_RADIUS = 0.235;
    private static final int BODY_STATIONS = 44;
    private static final int BODY_RING = 28;
    private static final int CABIN_STATIONS = 30;
    private static final int CABIN_RING = 16;

    private static final Profile LOWER_TOP = new Profile(
            -2.32, 0.74, -2.29, 0.85, -2.22, 0.93, -2.06, 0.975, -1.66, 0.985, -1.16, 0.965,
            0.0, 0.945, 0.94, 0.925, 1.34, 0.865, 1.84, 0.785, 2.19, 0.70, 2.34, 0.61, 2.40, 0.52);
    private static final Profile LOWER_BOTTOM = new Profile(
            -2.32, 0.52, -2.29, 0.38, -2.22, 0.30, -1.96, 0.20, 1.96, 0.20, 2.24, 0.25,
            2.36, 0.30, 2.40, 0.40);
    private static final Profile HALF_WIDTH = new Profile(
            -2.32, 0.46, -2.29, 0.72, -2.22, 0.84, -1.86, 0.915, -0.46, 0.93, 0.84, 0.925,
            1.94, 0.895, 2.24, 0.80, 2.36, 0.64, 2.40, 0.42);
    private static final Profile ROOF = new Profile(
            -1.62, 0.975, -1.35, 1.12, -0.95, 1.30, -0.45, 1.415, 0.05, 1.43, 0.45, 1.33,
            0.75, 1.14, 0.98, 0.93);

    private ProceduralCar() {
    }

    static CarVisual build(VehicleParams params) {
        Group body = new Group();

        MeshView lower = new MeshView(MeshFactory.loft(lowerBodySections(), true, true));
        lower.setMaterial(Materials.glossy("#e6e8eb", "#ffffff", 28));
        lower.setCullFace(CullFace.NONE);

        MeshView cabin = new MeshView(MeshFactory.loft(cabinSections(), false, false));
        cabin.setMaterial(Materials.glossy("#14171c", "#6d7a88", 60));
        cabin.setCullFace(CullFace.NONE);
        body.getChildren().addAll(lower, cabin);

        double frontX = params.cgToFrontAxle();
        double rearX = -params.cgToRearAxle();
        double radius = params.wheelRadius();
        double[][] centres = {
                {frontX, -radius, VISUAL_HALF_TRACK}, {frontX, -radius, -VISUAL_HALF_TRACK},
                {rearX, -radius, VISUAL_HALF_TRACK}, {rearX, -radius, -VISUAL_HALF_TRACK}};

        PhongMaterial archMaterial = Materials.matte("#0c0d0f");
        for (double[] p : centres) {
            double side = Math.signum(p[2]);
            Cylinder arch = new Cylinder(radius + 0.07, 0.02, 40);
            arch.setMaterial(archMaterial);
            arch.getTransforms().addAll(new Translate(p[0], -radius, side * (VISUAL_HALF_TRACK + 0.075)),
                    new Rotate(90, Rotate.X_AXIS));
            body.getChildren().add(arch);
        }

        Box shadow = new Box(4.9, 0.002, 2.05);
        shadow.setMaterial(Materials.matte("#08090a"));
        shadow.getTransforms().add(new Translate(0.04, -0.004, 0));
        body.getChildren().add(0, shadow);

        Lamps lamps = new Lamps(body);

        Node[] wheels = new Node[4];
        for (int i = 0; i < 4; i++) {
            wheels[i] = wheel(Math.signum(centres[i][2]), radius);
        }
        return new CarVisual("Built-in model", body, wheels, centres, lamps::show, 4.72, 2.40, 0.62, 1.04);
    }

    /** Box lamps: headlights, tail bar, reversing lights and amber indicators at the corners. */
    private static final class Lamps {
        private final Box[] headlights = new Box[2];
        private final Box tail;
        private final Box[] reverse = new Box[2];
        private final Box[] leftIndicators = new Box[2];
        private final Box[] rightIndicators = new Box[2];

        private final PhongMaterial headOff = Materials.glossy("#9aa4ae", "#ffffff", 50);
        private final PhongMaterial headLow = Materials.glowing(Color.web("#eaf4ff"));
        private final PhongMaterial headHigh = Materials.glowing(Color.web("#ffffff"));
        private final PhongMaterial tailOff = Materials.matte("#3a0c0f");
        private final PhongMaterial tailDim = Materials.glowing(Color.web("#7a1418"));
        private final PhongMaterial tailBright = Materials.glowing(Color.web("#ff2b2b"));
        private final PhongMaterial reverseOff = Materials.matte("#2a2c30");
        private final PhongMaterial reverseOn = Materials.glowing(Color.web("#f4f6f8"));
        private final PhongMaterial amberOff = Materials.matte("#4a3410");
        private final PhongMaterial amberOn = Materials.glowing(Color.web("#ffa21a"));

        Lamps(Group body) {
            for (int i = 0; i < 2; i++) {
                double side = i == 0 ? 1 : -1;
                headlights[i] = lamp(body, 0.06, 0.035, 0.34, 2.33, -0.625, side * 0.52, headOff);
                reverse[i] = lamp(body, 0.05, 0.04, 0.16, -2.30, -0.62, side * 0.42, reverseOff);
            }
            tail = lamp(body, 0.05, 0.045, 1.36, -2.29, -0.87, 0, tailOff);
            leftIndicators[0] = lamp(body, 0.05, 0.03, 0.12, 2.32, -0.66, 0.80, amberOff);
            leftIndicators[1] = lamp(body, 0.05, 0.04, 0.10, -2.28, -0.87, 0.74, amberOff);
            rightIndicators[0] = lamp(body, 0.05, 0.03, 0.12, 2.32, -0.66, -0.80, amberOff);
            rightIndicators[1] = lamp(body, 0.05, 0.04, 0.10, -2.28, -0.87, -0.74, amberOff);
        }

        private static Box lamp(Group body, double sx, double sy, double sz, double x, double y, double z,
                                PhongMaterial material) {
            Box box = new Box(sx, sy, sz);
            box.setMaterial(material);
            box.getTransforms().add(new Translate(x, y, z));
            body.getChildren().add(box);
            return box;
        }

        void show(LightState s) {
            PhongMaterial head = s.highBeam() ? headHigh : s.lowBeam() ? headLow : headOff;
            PhongMaterial rev = s.reverseLights() ? reverseOn : reverseOff;
            for (int i = 0; i < 2; i++) {
                headlights[i].setMaterial(head);
                reverse[i].setMaterial(rev);
                leftIndicators[i].setMaterial(s.leftLit() ? amberOn : amberOff);
                rightIndicators[i].setMaterial(s.rightLit() ? amberOn : amberOff);
            }
            tail.setMaterial(s.brakeLights() ? tailBright : s.tailLights() ? tailDim : tailOff);
        }
    }

    private static Group wheel(double side, double radius) {
        Group spinning = new Group();

        Cylinder tyre = new Cylinder(radius, TYRE_WIDTH, 48);
        tyre.setMaterial(Materials.matte("#151617"));
        tyre.getTransforms().add(new Rotate(90, Rotate.X_AXIS));

        Cylinder rim = new Cylinder(RIM_RADIUS, 0.02, 40);
        rim.setMaterial(Materials.glossy("#8f969e", "#ffffff", 40));
        rim.getTransforms().addAll(new Translate(0, 0, side * (TYRE_WIDTH / 2 + 0.004)),
                new Rotate(90, Rotate.X_AXIS));
        spinning.getChildren().addAll(tyre, rim);

        PhongMaterial spokeMaterial = Materials.matte("#2b2e33");
        for (int k = 0; k < 5; k++) {
            Box spoke = new Box(RIM_RADIUS * 1.9, 0.045, 0.012);
            spoke.setMaterial(spokeMaterial);
            spoke.getTransforms().addAll(new Translate(0, 0, side * (TYRE_WIDTH / 2 + 0.016)),
                    new Rotate(k * 36, Rotate.Z_AXIS));
            spinning.getChildren().add(spoke);
        }
        return spinning;
    }

    /** Cross-sections of the painted lower body: rounded boxes following the side profile. */
    private static List<double[][]> lowerBodySections() {
        List<double[][]> sections = new ArrayList<>();
        double min = LOWER_TOP.minX();
        double max = LOWER_TOP.maxX();
        for (int s = 0; s < BODY_STATIONS; s++) {
            // Cosine spacing: more sections near the rounded nose and tail.
            double t = 0.5 - 0.5 * Math.cos(Math.PI * s / (BODY_STATIONS - 1));
            double x = min + (max - min) * t;
            double top = LOWER_TOP.at(x);
            double bottom = LOWER_BOTTOM.at(x);
            double halfHeight = (top - bottom) / 2;
            double mid = (top + bottom) / 2;
            double halfWidth = HALF_WIDTH.at(x);
            double[][] ring = new double[BODY_RING][];
            for (int k = 0; k < BODY_RING; k++) {
                double a = 2 * Math.PI * k / BODY_RING;
                double z = halfWidth * superellipse(Math.cos(a), 5);
                double h = mid + halfHeight * superellipse(Math.sin(a), 4);
                ring[k] = new double[] {x, -h, z};
            }
            sections.add(ring);
        }
        return sections;
    }

    /** Cross-sections of the glass cabin: an arch from one side of the belt line to the other. */
    private static List<double[][]> cabinSections() {
        List<double[][]> sections = new ArrayList<>();
        double min = ROOF.minX();
        double max = ROOF.maxX();
        for (int s = 0; s < CABIN_STATIONS; s++) {
            double x = min + (max - min) * s / (CABIN_STATIONS - 1);
            double bottom = LOWER_TOP.at(x) - 0.02;
            double top = Math.max(bottom, ROOF.at(x));
            double widthBottom = HALF_WIDTH.at(x) - 0.06;
            double widthTop = widthBottom - 0.28;
            double[][] ring = new double[CABIN_RING][];
            for (int k = 0; k < CABIN_RING; k++) {
                double a = Math.PI * k / (CABIN_RING - 1);
                double up = Math.pow(Math.sin(a), 0.45);
                double across = superellipse(Math.cos(a), 1 / 0.45 * 2);
                double halfWidth = widthBottom + (widthTop - widthBottom) * up;
                ring[k] = new double[] {x, -(bottom + (top - bottom) * up), halfWidth * across};
            }
            sections.add(ring);
        }
        return sections;
    }

    /** Signed power used for superellipse shapes: exponent n gives squarer shapes as n grows. */
    private static double superellipse(double value, double n) {
        return Math.signum(value) * Math.pow(Math.abs(value), 2 / n);
    }
}
