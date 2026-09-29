package com.selfdriving.ui.render;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.SpotLight;
import javafx.scene.paint.Color;
import javafx.scene.shape.Box;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.vehicle.LightState;
import com.selfdriving.vehicle.VehicleState;

/**
 * The car in 3D. Holds a {@link CarVisual} (the built-in model, or a model file once it has
 * loaded) and animates it: pose on the road, body pitch, roll and heave from the suspension,
 * wheels spinning at their real speed and steering with their own Ackermann angles, lamps,
 * headlight beams on the road, and optional tyre force arrows.
 *
 * <p>Local frame: X forward, Y down, Z left, origin on the ground under the centre of gravity.
 * The model only reads snapshots; it never changes the simulation.
 */
public final class CarModel {

    /** Force arrow length per newton, m/N. */
    private static final double ARROW_SCALE = 1.0 / 2500;

    private static final Color BEAM_LOW = Color.web("#e4eaf2");
    private static final Color BEAM_HIGH = Color.web("#ffffff");

    private final Group root = new Group();
    private final Affine pose = new Affine();
    private final Group body = new Group();
    private final Translate bodyHeave = new Translate();
    private final Rotate bodyPitch;
    private final Rotate bodyRoll;

    private final Group[] wheelMounts = new Group[4];
    private final Translate[] wheelPosition = new Translate[4];
    private final Rotate[] wheelSteer = new Rotate[4];
    private final Group[] wheelSpinners = new Group[4];
    private final Rotate[] wheelSpin = new Rotate[4];

    private final Box[] forceArrows = new Box[4];
    private final Rotate[] arrowRotate = new Rotate[4];
    private final Translate[] arrowOffset = new Translate[4];
    private final Group arrows = new Group();

    private final SpotLight[] beams = new SpotLight[2];
    private final Translate[] beamPosition = new Translate[2];

    private CarVisual visual;
    private LightState shownLights;

    public CarModel(VehicleParams params) {
        double cgHeight = params.cgHeight();
        bodyPitch = new Rotate(0, 0, -cgHeight, 0, Rotate.Z_AXIS);
        bodyRoll = new Rotate(0, 0, -cgHeight, 0, Rotate.X_AXIS);
        body.getTransforms().addAll(bodyHeave, bodyPitch, bodyRoll);

        Group wheels = new Group();
        for (int i = 0; i < 4; i++) {
            wheelSpin[i] = new Rotate(0, Rotate.Z_AXIS);
            wheelSpinners[i] = new Group();
            wheelSpinners[i].getTransforms().add(wheelSpin[i]);
            wheelSteer[i] = new Rotate(0, Rotate.Y_AXIS);
            wheelPosition[i] = new Translate();
            wheelMounts[i] = new Group(wheelSpinners[i]);
            wheelMounts[i].getTransforms().addAll(wheelPosition[i], wheelSteer[i]);
            wheels.getChildren().add(wheelMounts[i]);
        }

        double frontX = params.cgToFrontAxle();
        double rearX = -params.cgToRearAxle();
        double halfTrack = params.trackWidth() / 2;
        double[][] arrowPositions = {{frontX, halfTrack}, {frontX, -halfTrack}, {rearX, halfTrack}, {rearX, -halfTrack}};
        for (int i = 0; i < 4; i++) {
            buildArrow(i, arrowPositions[i]);
        }
        arrows.setVisible(false);

        Group beamGroup = new Group();
        for (int i = 0; i < 2; i++) {
            SpotLight beam = new SpotLight(BEAM_LOW);
            beam.setDirection(new Point3D(1, 0.16, 0));
            beam.setInnerAngle(14);
            beam.setOuterAngle(34);
            beam.setFalloff(1.2);
            beamPosition[i] = new Translate();
            beam.getTransforms().add(beamPosition[i]);
            beam.setLightOn(false);
            beams[i] = beam;
            beamGroup.getChildren().add(beam);
        }

        root.getTransforms().add(pose);
        root.getChildren().addAll(wheels, body, arrows, beamGroup);
        install(ProceduralCar.build(params));
    }

    public Group node() {
        return root;
    }

    /** Name of the model currently shown. */
    public String modelName() {
        return visual.name();
    }

    /** Replaces the car's look (call on the JavaFX thread). */
    public void install(CarVisual newVisual) {
        visual = newVisual;
        body.getChildren().setAll(newVisual.body());
        for (int i = 0; i < 4; i++) {
            double[] c = newVisual.wheelCentres()[i];
            wheelPosition[i].setX(c[0]);
            wheelPosition[i].setY(c[1]);
            wheelPosition[i].setZ(c[2]);
            wheelSpinners[i].getChildren().setAll(newVisual.wheels()[i]);
        }
        for (int i = 0; i < 2; i++) {
            double side = i == 0 ? 1 : -1;
            beamPosition[i].setX(newVisual.frontOverhang() - 0.05);
            beamPosition[i].setY(-newVisual.lampHeight());
            beamPosition[i].setZ(side * newVisual.lampSpacing() / 2);
        }
        shownLights = null;
    }

    /** Shows or hides the tyre force arrows. */
    public void setForcesVisible(boolean visible) {
        arrows.setVisible(visible);
    }

    public boolean forcesVisible() {
        return arrows.isVisible();
    }

    /** Moves and animates the model to match a snapshot. */
    public void update(VehicleState s, LightState lights) {
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

        if (!lights.equals(shownLights)) {
            visual.lamps().show(lights);
            updateBeams(lights);
            shownLights = lights;
        }
    }

    /** Headlight pools on the road: short and wide when dipped, long and bright on main beam. */
    private void updateBeams(LightState lights) {
        boolean on = lights.lowBeam() || lights.highBeam();
        for (SpotLight beam : beams) {
            beam.setLightOn(on);
            if (!on) {
                continue;
            }
            if (lights.highBeam()) {
                beam.setColor(BEAM_HIGH);
                beam.setDirection(new Point3D(1, 0.025, 0));
                beam.setInnerAngle(10);
                beam.setOuterAngle(24);
                beam.setLinearAttenuation(0.0);
                beam.setQuadraticAttenuation(0.00002);
                beam.setMaxRange(220);
            } else {
                beam.setColor(BEAM_LOW);
                beam.setDirection(new Point3D(1, 0.09, 0));
                beam.setInnerAngle(16);
                beam.setOuterAngle(38);
                beam.setLinearAttenuation(0.0);
                beam.setQuadraticAttenuation(0.00025);
                beam.setMaxRange(80);
            }
        }
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
}
