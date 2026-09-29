package com.selfdriving.ui.render;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;

import com.selfdriving.world.Marking;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.ProvingGround;
import com.selfdriving.world.Road;

/**
 * The proving ground in 3D: dark ground with a faint grid (so motion is visible anywhere),
 * asphalt, white paint and light poles around the circuit. Each layer is merged into a single
 * mesh, so the whole world is only a handful of draw calls.
 */
public final class WorldModel {

    private static final double GRID_SPACING = 20;
    private static final double GRID_LINE_WIDTH = 0.08;
    private static final double POLE_SPACING = 60;

    private final Group root = new Group();

    public WorldModel(ProvingGround ground) {
        double half = ProvingGround.GROUND_HALF_SIZE;

        MeshFactory.Builder groundMesh = new MeshFactory.Builder();
        MeshFactory.rectangle(groundMesh, -half, -half, half, half, 0);
        root.getChildren().add(view(groundMesh, Materials.matte("#17191d")));

        MeshFactory.Builder grid = new MeshFactory.Builder();
        for (double v = -half; v <= half + 1e-9; v += GRID_SPACING) {
            MeshFactory.rectangle(grid, -half, v - GRID_LINE_WIDTH / 2, half, v + GRID_LINE_WIDTH / 2, -0.004);
            MeshFactory.rectangle(grid, v - GRID_LINE_WIDTH / 2, -half, v + GRID_LINE_WIDTH / 2, half, -0.004);
        }
        root.getChildren().add(view(grid, Materials.matte("#22252a")));

        MeshFactory.Builder asphalt = new MeshFactory.Builder();
        for (Road road : ground.roads()) {
            MeshFactory.ribbon(asphalt, road.centre(), road.width(), -0.01);
        }
        root.getChildren().add(view(asphalt, Materials.matte("#34373d")));

        MeshFactory.Builder paint = new MeshFactory.Builder();
        MeshFactory.Builder transverse = new MeshFactory.Builder();
        for (Marking marking : ground.markings()) {
            MeshFactory.Builder target = marking.kind() == Marking.Kind.TRANSVERSE ? transverse : paint;
            MeshFactory.ribbon(target, marking.line(), marking.width(), -0.02);
        }
        root.getChildren().add(view(paint, Materials.matte("#d7dade")));
        root.getChildren().add(view(transverse, Materials.matte("#c9a227")));

        root.getChildren().add(poles(ground.roads().get(0)));
    }

    public Group node() {
        return root;
    }

    /** Light poles along the outside of a road, with glowing heads. */
    private static Group poles(Road road) {
        MeshFactory.Builder posts = new MeshFactory.Builder();
        MeshFactory.Builder heads = new MeshFactory.Builder();
        Polyline outside = road.centre().offset(-(road.width() / 2 + 3));
        double travelled = 0;
        double next = 0;
        Point2 previous = outside.points().get(0);
        for (Point2 p : outside.points()) {
            travelled += previous.distanceTo(p);
            previous = p;
            if (travelled >= next) {
                MeshFactory.box(posts, p.x(), -3.0, p.y(), 0.16, 6.0, 0.16);
                MeshFactory.box(heads, p.x(), -6.05, p.y(), 0.5, 0.12, 0.5);
                next += POLE_SPACING;
            }
        }
        Group group = new Group(view(posts, Materials.matte("#4a4e55")),
                view(heads, Materials.glowing(Color.web("#fff3d6"))));
        return group;
    }

    private static MeshView view(MeshFactory.Builder builder, javafx.scene.paint.Material material) {
        MeshView view = new MeshView(builder.build());
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        return view;
    }
}
