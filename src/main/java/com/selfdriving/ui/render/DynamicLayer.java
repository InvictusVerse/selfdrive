package com.selfdriving.ui.render;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Shape3D;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Translate;

import com.selfdriving.navigation.Route;
import com.selfdriving.sensors.SensorReadings;
import com.selfdriving.simulation.SimulationSnapshot;
import com.selfdriving.world.OrientedBox;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;

/**
 * Everything in the 3D view that changes while driving, drawn the way an autopilot display
 * does: other road users as clean shapes (blue when the autopilot is following or stopping for
 * them, red when emergency braking is triggered by them), the planned route as a blue ribbon on
 * the road, and optionally the lidar returns as green dots.
 */
final class DynamicLayer {

    private static final int LIDAR_DOT_STEP = 2;

    private final Group root = new Group();
    private final Group actorsGroup = new Group();
    private final Group routeGroup = new Group();
    private final Group lidarGroup = new Group();
    private final Map<Integer, ActorNode> actors = new HashMap<>();
    private final List<Box> lidarDots = new ArrayList<>();
    private long routeVersion = -1;
    private SensorReadings drawnReadings;

    private final PhongMaterial normal = Materials.glossy("#b4bac2", "#ffffff", 20);
    private final PhongMaterial lead = Materials.glowing(Color.web("#4f7cff"));
    private final PhongMaterial threat = Materials.glowing(Color.web("#ff3b3b"));
    private final PhongMaterial barrier = Materials.glowing(Color.web("#f08a24"));
    private final PhongMaterial routeMaterial = Materials.glowing(Color.web("#1f3f9c"));

    DynamicLayer() {
        lidarGroup.setVisible(false);
        root.getChildren().addAll(routeGroup, actorsGroup, lidarGroup);
    }

    Group node() {
        return root;
    }

    void setLidarVisible(boolean visible) {
        lidarGroup.setVisible(visible);
    }

    boolean lidarVisible() {
        return lidarGroup.isVisible();
    }

    void update(SimulationSnapshot s) {
        updateRoute(s.navigation() == null ? null : s.navigation().route());
        updateActors(s);
        if (lidarGroup.isVisible() && s.sensors() != drawnReadings) {
            updateLidar(s);
            drawnReadings = s.sensors();
        }
    }

    private void updateRoute(Route route) {
        long version = route == null ? 0 : route.version();
        if (version == routeVersion) {
            return;
        }
        routeVersion = version;
        routeGroup.getChildren().clear();
        if (route == null || route.size() < 2) {
            return;
        }
        List<Point2> points = new ArrayList<>(route.size());
        for (int i = 0; i < route.size(); i++) {
            points.add(new Point2(route.x(i), route.y(i)));
        }
        MeshFactory.Builder b = new MeshFactory.Builder();
        MeshFactory.ribbon(b, new Polyline(points, false), 0.8, -0.03);
        MeshView ribbon = new MeshView(b.build());
        ribbon.setMaterial(routeMaterial);
        ribbon.setCullFace(CullFace.NONE);
        routeGroup.getChildren().add(ribbon);
    }

    private void updateActors(SimulationSnapshot s) {
        int leadId = s.autopilot() != null ? s.autopilot().leadObjectId() : -1;
        int threatId = s.safety().emergencyBraking() ? s.safety().threatId() : -1;
        Set<Integer> present = new HashSet<>();
        for (SimulationSnapshot.ActorState a : s.actors()) {
            present.add(a.id());
            ActorNode node = actors.computeIfAbsent(a.id(), id -> {
                ActorNode created = build(a);
                actorsGroup.getChildren().add(created.group);
                return created;
            });
            node.place(a.box());
            PhongMaterial material = a.id() == threatId ? threat
                    : a.id() == leadId ? lead
                    : a.kind() == com.selfdriving.world.Obstacle.Kind.BARRIER ? barrier : normal;
            node.setMaterial(material);
        }
        actors.entrySet().removeIf(entry -> {
            if (!present.contains(entry.getKey())) {
                actorsGroup.getChildren().remove(entry.getValue().group);
                return true;
            }
            return false;
        });
    }

    private void updateLidar(SimulationSnapshot s) {
        SensorReadings r = s.sensors();
        float[] angles = r.lidarAngles();
        float[] ranges = r.lidarRanges();
        int needed = (ranges.length + LIDAR_DOT_STEP - 1) / LIDAR_DOT_STEP;
        while (lidarDots.size() < needed) {
            Box dot = new Box(0.14, 0.14, 0.14);
            dot.setMaterial(Materials.glowing(Color.web("#3ddc84")));
            dot.getTransforms().add(new Translate());
            lidarDots.add(dot);
            lidarGroup.getChildren().add(dot);
        }
        double x = s.vehicle().x();
        double y = s.vehicle().y();
        double heading = s.vehicle().heading();
        for (int k = 0; k < lidarDots.size(); k++) {
            Box dot = lidarDots.get(k);
            int i = k * LIDAR_DOT_STEP;
            if (i >= ranges.length || Float.isNaN(ranges[i])) {
                dot.setVisible(false);
                continue;
            }
            double a = heading + angles[i];
            Translate t = (Translate) dot.getTransforms().get(0);
            t.setX(x + Math.cos(a) * ranges[i]);
            t.setY(-0.9);
            t.setZ(y + Math.sin(a) * ranges[i]);
            dot.setVisible(true);
        }
    }

    private ActorNode build(SimulationSnapshot.ActorState a) {
        List<Shape3D> shapes = new ArrayList<>();
        Group body = new Group();
        switch (a.kind()) {
            case CAR -> {
                double l = 2 * a.box().halfLength();
                double w = 2 * a.box().halfWidth();
                shapes.add(place(new Box(l, 0.8, w), 0, -0.55, 0));
                shapes.add(place(new Box(l * 0.52, 0.55, w * 0.86), -l * 0.06, -1.2, 0));
                for (double[] wheel : new double[][] {{l * 0.32, w / 2}, {l * 0.32, -w / 2}, {-l * 0.32, w / 2},
                        {-l * 0.32, -w / 2}}) {
                    Cylinder tyre = new Cylinder(0.33, 0.22, 24);
                    tyre.setMaterial(Materials.matte("#141516"));
                    tyre.getTransforms().addAll(new Translate(wheel[0], -0.33, wheel[1]),
                            new javafx.scene.transform.Rotate(90, javafx.scene.transform.Rotate.X_AXIS));
                    body.getChildren().add(tyre);
                }
            }
            case PEDESTRIAN -> {
                shapes.add(place(new Cylinder(0.2, 1.3), 0, -0.65, 0));
                shapes.add(place(new Sphere(0.14, 16), 0, -1.5, 0));
            }
            case BARRIER -> {
                shapes.add(place(new Box(0.5, 1.0, 2 * a.box().halfWidth()), 0, -0.5, 0));
                Box stripe = new Box(0.52, 0.18, 2 * a.box().halfWidth() + 0.02);
                stripe.setMaterial(Materials.glowing(Color.web("#f4f6f8")));
                stripe.getTransforms().add(new Translate(0, -0.75, 0));
                body.getChildren().add(stripe);
            }
            default -> shapes.add(place(new Box(2 * a.box().halfLength(), a.height(), 2 * a.box().halfWidth()),
                    0, -a.height() / 2, 0));
        }
        body.getChildren().addAll(shapes);
        return new ActorNode(body, shapes);
    }

    private static Shape3D place(Shape3D shape, double x, double y, double z) {
        shape.getTransforms().add(new Translate(x, y, z));
        return shape;
    }

    /** One actor's nodes and pose. */
    private static final class ActorNode {
        final Group group;
        final List<Shape3D> shapes;
        final Affine pose = new Affine();
        PhongMaterial current;

        ActorNode(Group group, List<Shape3D> shapes) {
            this.group = group;
            this.shapes = shapes;
            group.getTransforms().add(pose);
        }

        void place(OrientedBox box) {
            double c = Math.cos(box.heading());
            double s = Math.sin(box.heading());
            pose.setToTransform(c, 0, -s, box.cx(), 0, 1, 0, 0, s, 0, c, box.cy());
        }

        void setMaterial(PhongMaterial material) {
            if (material != current) {
                current = material;
                for (Node n : shapes) {
                    ((Shape3D) n).setMaterial(material);
                }
            }
        }
    }
}
