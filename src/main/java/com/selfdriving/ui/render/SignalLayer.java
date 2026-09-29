package com.selfdriving.ui.render;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import com.selfdriving.world.Point2;
import com.selfdriving.world.RoadNetwork;

/**
 * Traffic lights in 3D: a pole at the left kerb of every signalised approach, at the stop line,
 * with red, amber and green lamps that follow the junction's signal plan.
 */
final class SignalLayer {

    private static final double HEIGHT = 3.6;

    private final Group root = new Group();
    private final RoadNetwork network;
    private final List<Head> heads = new ArrayList<>();

    private final PhongMaterial redOn = Materials.glowing(Color.web("#ff3030"));
    private final PhongMaterial amberOn = Materials.glowing(Color.web("#ffb020"));
    private final PhongMaterial greenOn = Materials.glowing(Color.web("#30e070"));
    private final PhongMaterial off = Materials.matte("#1e2024");

    /** One signal head and the approach it controls. */
    private final class Head {
        final RoadNetwork.Link link;
        final Box red = lamp();
        final Box amber = lamp();
        final Box green = lamp();
        RoadNetwork.Signal shown;

        Head(RoadNetwork.Link link) {
            this.link = link;
        }

        void show(RoadNetwork.Signal s) {
            if (s == shown) {
                return;
            }
            shown = s;
            red.setMaterial(s == RoadNetwork.Signal.RED ? redOn : off);
            amber.setMaterial(s == RoadNetwork.Signal.AMBER ? amberOn : off);
            green.setMaterial(s == RoadNetwork.Signal.GREEN ? greenOn : off);
        }
    }

    SignalLayer(RoadNetwork network) {
        this.network = network;
        MeshFactory.Builder poles = new MeshFactory.Builder();
        MeshFactory.Builder housings = new MeshFactory.Builder();
        for (RoadNetwork.Junction j : network.junctions()) {
            if (!j.isSignalised()) {
                continue;
            }
            for (int id : j.incoming()) {
                RoadNetwork.Link link = network.link(id);
                if (!link.isRendered() || link.length() < 8) {
                    continue;
                }
                double arc = link.stopArc(true);
                Point2 c = link.centre().pointAt(arc);
                double h = link.centre().headingAt(arc);
                double side = link.leftEdge() + 0.9;
                double x = c.x() - Math.sin(h) * side;
                double z = c.y() + Math.cos(h) * side;
                MeshFactory.box(poles, x, -HEIGHT / 2, z, 0.14, HEIGHT, 0.14);
                MeshFactory.box(housings, x, -HEIGHT - 0.45, z, 0.32, 1.05, 0.32);
                Head head = new Head(link);
                Group lamps = new Group(head.red, head.amber, head.green);
                head.red.getTransforms().add(new Translate(0, -0.33, 0));
                head.green.getTransforms().add(new Translate(0, 0.33, 0));
                // Lamps face the approaching traffic.
                lamps.getTransforms().addAll(new Translate(x, -HEIGHT - 0.45, z),
                        new Rotate(-Math.toDegrees(h), Rotate.Y_AXIS), new Translate(-0.17, 0, 0));
                root.getChildren().add(lamps);
                heads.add(head);
            }
        }
        javafx.scene.shape.MeshView poleView = new javafx.scene.shape.MeshView(poles.build());
        poleView.setMaterial(Materials.matte("#3c4047"));
        javafx.scene.shape.MeshView housingView = new javafx.scene.shape.MeshView(housings.build());
        housingView.setMaterial(Materials.matte("#15171a"));
        root.getChildren().addAll(0, List.of(poleView, housingView));
    }

    Group node() {
        return root;
    }

    void update(double time) {
        for (Head head : heads) {
            head.show(network.signal(head.link, time));
        }
    }

    private Box lamp() {
        Box b = new Box(0.04, 0.24, 0.24);
        b.setMaterial(off);
        return b;
    }
}
