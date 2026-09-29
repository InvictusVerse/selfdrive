package com.selfdriving.ui.render;

import javafx.geometry.Point3D;
import javafx.scene.AmbientLight;
import javafx.scene.DirectionalLight;
import javafx.scene.Group;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.vehicle.VehicleState;
import com.selfdriving.world.ProvingGround;

/**
 * The live 3D view: world, car, lights and camera in a resizable panel.
 */
public final class DrivingView {

    private final Pane container = new Pane();
    private final SubScene subScene;
    private final CarModel car;
    private final CameraRig cameraRig = new CameraRig();

    public DrivingView(ProvingGround ground, VehicleParams params) {
        car = new CarModel(params);
        WorldModel world = new WorldModel(ground);

        AmbientLight ambient = new AmbientLight(Color.web("#6d737c"));
        DirectionalLight sun = new DirectionalLight(Color.web("#d9dee6"));
        sun.setDirection(new Point3D(-0.35, 1, 0.45));

        Group root = new Group(world.node(), car.node(), ambient, sun);
        subScene = new SubScene(root, 800, 600, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Materials.BACKGROUND);
        subScene.setCamera(cameraRig.camera());
        subScene.widthProperty().bind(container.widthProperty());
        subScene.heightProperty().bind(container.heightProperty());
        container.getChildren().add(subScene);
        container.setMinSize(0, 0);
    }

    /** The panel to place in a layout. */
    public Pane node() {
        return container;
    }

    public CameraRig cameraRig() {
        return cameraRig;
    }

    public CarModel car() {
        return car;
    }

    /** Draws a new frame. */
    public void update(VehicleState state, double dt) {
        car.update(state);
        cameraRig.update(state, dt);
    }
}
