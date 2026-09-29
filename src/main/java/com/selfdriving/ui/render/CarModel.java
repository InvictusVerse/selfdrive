package com.selfdriving.ui.render;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import com.selfdriving.physics.Gear;
import com.selfdriving.physics.VehicleParams;
import com.selfdriving.vehicle.VehicleState;

/**
 * The car in 3D, built entirely in code: a lofted body shaped by smooth profiles, a dark glass
 * cabin, spinning and steering wheels, and lights that react to the pedals and gear.
 *
 * <p>Local frame: X forward, Y down, Z left, origin on the ground under the centre of gravity.
 * The model only reads {@link VehicleState}; it never changes the simulation.
 */
public final class CarModel {

    private static final double VISUAL_HALF_TRACK = 0.83;
    private static final double TYRE_WIDTH = 0.235;
    private static final double RIM_RADIUS = 0.235;
    private static final int BODY_STATIONS = 44;
    private static final int BODY_RING = 28;
    private static final int CABIN_STATIONS = 30;
    private static final int CABIN_RING = 16;

    /** Force arrow length per newton, m/N. */
    private static final double ARROW_SCALE = 1.0 / 2500;

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

    private final Group root = new Group();
    private final Affine pose = new Affine();
    private final Translate bodyHeave = new Translate();
    private final Rotate bodyPitch;
    private final Rotate bodyRoll;
    private final double cgHeight;

    private final Rotate[] wheelSteer = new Rotate[4];
    private final Rotate[] wheelSpin = new Rotate[4];
    private final Box[] forceArrows = new Box[4];
    private final Rotate[] arrowRotate = new Rotate[4];
    private final Translate[] arrowOffset = new Translate[4];
    private final Group arrows = new Group();

    private final Box tailLight;
    private final Box[] reverseLights = new Box[2];
    private final PhongMaterial tailDim = Materials.glowing(Color.web("#5c1014"));
    private final PhongMaterial tailBright = Materials.glowing(Color.web("#ff2b2b"));
    private final PhongMaterial reverseOff = Materials.matte("#2a2c30");
    private final PhongMaterial reverseOn = Materials.glowing(Color.web("#f4f6f8"));

    public CarModel(VehicleParams params) {
        cgHeight = params.cgHeight();
        bodyPitch = new Rotate(0, 0, -cgHeight, 0, Rotate.Z_AXIS);
        bodyRoll = new Rotate(0, 0, -cgHeight, 0, Rotate.X_AXIS);

        Group body = new Group();
        body.getTransforms().addAll(bodyHeave, bodyPitch, bodyRoll);

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
        double[][] wheelPositions = {
                {frontX, VISUAL_HALF_TRACK}, {frontX, -VISUAL_HALF_TRACK},
                {rearX, VISUAL_HALF_TRACK}, {rearX, -VISUAL_HALF_TRACK}};

        PhongMaterial archMaterial = Materials.matte("#0c0d0f");
        for (double[] p : wheelPositions) {
            double side = Math.signum(p[1]);
            Cylinder arch = new Cylinder(radius + 0.07, 0.02, 40);
            arch.setMaterial(archMaterial);
            arch.getTransforms().addAll(new Translate(p[0], -radius, side * (VISUAL_HALF_TRACK + 0.075)),
                    new Rotate(90, Rotate.X_AXIS));
            body.getChildren().add(arch);
        }

        PhongMaterial headlight = Materials.glowing(Color.web("#eaf4ff"));
        for (double side : new double[] {1, -1}) {
            Box lamp = new Box(0.06, 0.035, 0.34);
            lamp.setMaterial(headlight);
            lamp.getTransforms().add(new Translate(2.33, -0.625, side * 0.52));
            body.getChildren().add(lamp);
        }
        tailLight = new Box(0.05, 0.045, 1.36);
        tailLight.setMaterial(tailDim);
        tailLight.getTransforms().add(new Translate(-2.29, -0.87, 0));
        body.getChildren().add(tailLight);
        for (int i = 0; i < 2; i++) {
            Box lamp = new Box(0.05, 0.04, 0.16);
            lamp.setMaterial(reverseOff);
            lamp.getTransforms().add(new Translate(-2.30, -0.62, (i == 0 ? 1 : -1) * 0.42));
            reverseLights[i] = lamp;
            body.getChildren().add(lamp);
        }

        Box shadow = new Box(4.9, 0.002, 2.05);
        shadow.setMaterial(Materials.matte("#08090a"));
        shadow.getTransforms().add(new Translate(0.04, -0.004, 0));

        Group wheels = new Group();
        for (int i = 0; i < 4; i++) {
            wheels.getChildren().add(buildWheel(i, wheelPositions[i], radius));
            buildArrow(i, wheelPositions[i]);
        }
        arrows.setVisible(false);

        root.getTransforms().add(pose);
        root.getChildren().addAll(shadow, wheels, body, arrows);
    }

    public Group node() {
        return root;
    }

    /** Shows or hides the tyre force arrows. */
    public void setForcesVisible(boolean visible) {
        arrows.setVisible(visible);
    }

    public boolean forcesVisible() {
        return arrows.isVisible();
    }

    /** Moves and animates the model to match a snapshot. */
    public void update(VehicleState s) {
        double c = Math.cos(s.heading());
        double sn = Math.sin(s.heading());
        // Columns: car forward -> (cos, 0, sin), car down -> (0, 1, 0), car left -> (-sin, 0, cos).
        pose.setToTransform(c, 0, -sn, s.x(), 0, 1, 0, 0, sn, 0, c, s.y());

        bodyHeave.setY(s.heave());
        bodyPitch.setAngle(Math.toDegrees(s.pitch()));
        bodyRoll.setAngle(Math.toDegrees(s.roll()));

        for (int i = 0; i < 4; i++) {
            VehicleState.WheelState w = s.wheels().get(i);
            wheelSteer[i].setAngle(-Math.toDegrees(w.steerAngle()));
            wheelSpin[i].setAngle(Math.toDegrees(w.rotation()));
            if (arrows.isVisible()) {
                double force = Math.hypot(w.forceX(), w.forceY());
                double length = Math.max(0.01, force * ARROW_SCALE);
                forceArrows[i].setWidth(length);
                arrowOffset[i].setX(length / 2);
                arrowRotate[i].setAngle(-Math.toDegrees(Math.atan2(w.forceY(), w.forceX())));
            }
        }

        tailLight.setMaterial(s.brake() > 0.05 ? tailBright : tailDim);
        PhongMaterial reverse = s.gear() == Gear.REVERSE ? reverseOn : reverseOff;
        reverseLights[0].setMaterial(reverse);
        reverseLights[1].setMaterial(reverse);
    }

    private Group buildWheel(int index, double[] position, double radius) {
        double side = Math.signum(position[1]);
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

        wheelSteer[index] = new Rotate(0, Rotate.Y_AXIS);
        wheelSpin[index] = new Rotate(0, Rotate.Z_AXIS);
        spinning.getTransforms().add(wheelSpin[index]);

        Group wheel = new Group(spinning);
        wheel.getTransforms().addAll(new Translate(position[0], -radius, position[1]), wheelSteer[index]);
        return wheel;
    }

    private void buildArrow(int index, double[] position) {
        Box arrow = new Box(1, 0.07, 0.07);
        arrow.setMaterial(Materials.glowing(Color.web("#37d6ff")));
        arrowRotate[index] = new Rotate(0, Rotate.Y_AXIS);
        arrowOffset[index] = new Translate();
        arrow.getTransforms().addAll(new Translate(position[0], -0.08, position[1]), arrowRotate[index],
                arrowOffset[index]);
        forceArrows[index] = arrow;
        arrows.getChildren().add(arrow);
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
