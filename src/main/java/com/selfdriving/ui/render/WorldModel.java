package com.selfdriving.ui.render;

import java.util.List;

import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;

import com.selfdriving.world.Building;
import com.selfdriving.world.Marking;
import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;
import com.selfdriving.world.Road;
import com.selfdriving.world.RoadNetwork;
import com.selfdriving.world.Triangulator;
import com.selfdriving.world.World;
import com.selfdriving.world.map.MapData;

/**
 * The static world in 3D: dark ground with a faint grid (so motion is visible anywhere),
 * asphalt with its paint, light poles around the circuit, parks, water, and the city's
 * buildings extruded from their real footprints. Each layer is merged into a single mesh, so the
 * whole world is only a handful of draw calls.
 */
public final class WorldModel {

    private static final double GRID_SPACING = 20;
    private static final double GRID_LINE_WIDTH = 0.08;
    private static final double POLE_SPACING = 60;
    private static final int DISC_SIDES = 20;

    private final Group root = new Group();

    public WorldModel(World ground) {
        double[] b = ground.bounds();
        double minX = Math.floor((b[0] - 200) / GRID_SPACING) * GRID_SPACING;
        double minY = Math.floor((b[1] - 200) / GRID_SPACING) * GRID_SPACING;
        double maxX = Math.ceil((b[2] + 200) / GRID_SPACING) * GRID_SPACING;
        double maxY = Math.ceil((b[3] + 200) / GRID_SPACING) * GRID_SPACING;

        MeshFactory.Builder groundMesh = new MeshFactory.Builder();
        MeshFactory.rectangle(groundMesh, minX, minY, maxX, maxY, 0);
        root.getChildren().add(view(groundMesh, Materials.matte("#17191d")));

        MeshFactory.Builder grid = new MeshFactory.Builder();
        for (double v = minY; v <= maxY + 1e-9; v += GRID_SPACING) {
            MeshFactory.rectangle(grid, minX, v - GRID_LINE_WIDTH / 2, maxX, v + GRID_LINE_WIDTH / 2, -0.004);
        }
        for (double v = minX; v <= maxX + 1e-9; v += GRID_SPACING) {
            MeshFactory.rectangle(grid, v - GRID_LINE_WIDTH / 2, minY, v + GRID_LINE_WIDTH / 2, maxY, -0.004);
        }
        root.getChildren().add(view(grid, Materials.matte("#22252a")));

        MeshFactory.Builder parks = new MeshFactory.Builder();
        MeshFactory.Builder water = new MeshFactory.Builder();
        for (MapData.Area area : ground.areas()) {
            polygon(area.kind().equals("water") ? water : parks, area.outline(), -0.006);
        }
        root.getChildren().add(view(parks, Materials.matte("#1b2820")));
        root.getChildren().add(view(water, Materials.glossy("#14202c", "#3a4a5a", 40)));

        MeshFactory.Builder asphalt = new MeshFactory.Builder();
        for (Road road : ground.roads()) {
            MeshFactory.ribbon(asphalt, road.centre(), road.width(), -0.01);
        }
        junctionFills(asphalt, ground.network());
        root.getChildren().add(view(asphalt, Materials.matte("#34373d")));

        MeshFactory.Builder paint = new MeshFactory.Builder();
        MeshFactory.Builder yellow = new MeshFactory.Builder();
        MeshFactory.Builder transverse = new MeshFactory.Builder();
        for (Marking marking : ground.markings()) {
            MeshFactory.Builder target = switch (marking.kind()) {
                case TRANSVERSE -> transverse;
                case CENTRE -> yellow;
                default -> paint;
            };
            MeshFactory.ribbon(target, marking.line(), marking.width(), -0.02);
        }
        root.getChildren().add(view(paint, Materials.matte("#d7dade")));
        root.getChildren().add(view(yellow, Materials.matte("#c9a227")));
        root.getChildren().add(view(transverse, Materials.matte("#c9a227")));

        root.getChildren().add(poles(ground.provingGround().roads().get(0)));

        MeshFactory.Builder walls = new MeshFactory.Builder();
        MeshFactory.Builder roofs = new MeshFactory.Builder();
        for (Building building : ground.buildings()) {
            extrude(walls, roofs, building);
        }
        MeshView wallView = view(walls, Materials.glossy("#6c727b", "#24272b", 8));
        wallView.setCullFace(CullFace.BACK);
        MeshView roofView = view(roofs, Materials.matte("#8a909a"));
        roofView.setCullFace(CullFace.BACK);
        root.getChildren().addAll(wallView, roofView);
    }

    public Group node() {
        return root;
    }

    /** Asphalt discs where roads meet, so corners between road pieces are filled. */
    private static void junctionFills(MeshFactory.Builder b, RoadNetwork network) {
        for (RoadNetwork.Junction j : network.junctions()) {
            if (j.isBoundary()) {
                continue;
            }
            double radius = 0;
            for (List<Integer> ids : List.of(j.incoming(), j.outgoing())) {
                for (int id : ids) {
                    RoadNetwork.Link link = network.link(id);
                    if (!link.isRendered()) {
                        continue;
                    }
                    double width = link.lanes() * link.laneWidth();
                    if (link.reverseId() >= 0) {
                        RoadNetwork.Link back = network.link(link.reverseId());
                        width += back.lanes() * back.laneWidth();
                    }
                    radius = Math.max(radius, width / 2);
                }
            }
            if (radius <= 0) {
                continue;
            }
            boolean crossing = j.incoming().size() + j.outgoing().size() > 2;
            for (Point2 m : j.members()) {
                disc(b, m.x(), m.y(), radius + (crossing ? 3.0 : 0.3), -0.009);
            }
        }
    }

    private static void disc(MeshFactory.Builder b, double cx, double cz, double r, double y) {
        int centre = b.vertex(cx, y, cz);
        int[] ring = new int[DISC_SIDES];
        for (int i = 0; i < DISC_SIDES; i++) {
            double a = 2 * Math.PI * i / DISC_SIDES;
            ring[i] = b.vertex(cx + r * Math.cos(a), y, cz + r * Math.sin(a));
        }
        for (int i = 0; i < DISC_SIDES; i++) {
            // Clockwise seen from above (the builder flips it to face up).
            b.triangle(centre, ring[(i + 1) % DISC_SIDES], ring[i], 0);
        }
    }

    /** A flat polygon on the ground. */
    private static void polygon(MeshFactory.Builder b, List<Point2> outline, double y) {
        int[] tris = Triangulator.triangulate(outline);
        int[] ids = new int[outline.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = b.vertex(outline.get(i).x(), y, outline.get(i).y());
        }
        for (int t = 0; t < tris.length; t += 3) {
            b.triangle(ids[tris[t]], ids[tris[t + 2]], ids[tris[t + 1]], 0);
        }
    }

    /** Walls around a counter-clockwise footprint, and a flat roof. */
    private static void extrude(MeshFactory.Builder walls, MeshFactory.Builder roofs, Building building) {
        List<Point2> f = building.footprint();
        double top = -building.height();
        int n = f.size();
        for (int i = 0; i < n; i++) {
            Point2 p = f.get(i);
            Point2 q = f.get((i + 1) % n);
            int pt = walls.vertex(p.x(), top, p.y());
            int qt = walls.vertex(q.x(), top, q.y());
            int qb = walls.vertex(q.x(), 0, q.y());
            int pb = walls.vertex(p.x(), 0, p.y());
            walls.quad(pt, qt, qb, pb, 0);
        }
        polygon(roofs, f, top);
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
        return new Group(view(posts, Materials.matte("#4a4e55")), view(heads, Materials.glowing(Color.web("#fff3d6"))));
    }

    private static MeshView view(MeshFactory.Builder builder, javafx.scene.paint.Material material) {
        MeshView view = new MeshView(builder.build());
        view.setMaterial(material);
        view.setCullFace(CullFace.NONE);
        return view;
    }
}
