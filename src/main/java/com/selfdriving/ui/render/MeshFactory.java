package com.selfdriving.ui.render;

import java.util.List;

import javafx.scene.shape.TriangleMesh;

import com.selfdriving.world.Point2;
import com.selfdriving.world.Polyline;

/**
 * Builds triangle meshes in code (no model files).
 *
 * <p>Coordinates are JavaFX 3D coordinates: X = east (or car forward), Y = down, Z = north
 * (or car left). World point (x, y) on the ground maps to (x, 0, y).
 */
final class MeshFactory {

    private MeshFactory() {
    }

    /**
     * Collects vertices and triangles, then produces a {@link TriangleMesh}.
     * Every face uses the same single texture coordinate (materials are plain colours).
     */
    static final class Builder {
        private float[] points = new float[3 * 256];
        private int pointCount;
        private int[] faces = new int[6 * 256];
        private int faceCount;
        private int[] smoothing = new int[256];

        int vertex(double x, double y, double z) {
            if (3 * (pointCount + 1) > points.length) {
                points = java.util.Arrays.copyOf(points, points.length * 2);
            }
            points[3 * pointCount] = (float) x;
            points[3 * pointCount + 1] = (float) y;
            points[3 * pointCount + 2] = (float) z;
            return pointCount++;
        }

        /**
         * Triangle a-b-c. The corner order is reversed on the way into the mesh so that the
         * faces made by the helpers in this class face outwards (upwards for flat pieces) and
         * are lit from the correct side.
         */
        void triangle(int a, int b, int c, int smoothingGroup) {
            if (6 * (faceCount + 1) > faces.length) {
                faces = java.util.Arrays.copyOf(faces, faces.length * 2);
                smoothing = java.util.Arrays.copyOf(smoothing, smoothing.length * 2);
            }
            int base = 6 * faceCount;
            faces[base] = a;
            faces[base + 2] = c;
            faces[base + 4] = b;
            smoothing[faceCount] = smoothingGroup;
            faceCount++;
        }

        /** Quad a-b-c-d as two triangles. */
        void quad(int a, int b, int c, int d, int smoothingGroup) {
            triangle(a, b, c, smoothingGroup);
            triangle(a, c, d, smoothingGroup);
        }

        TriangleMesh build() {
            TriangleMesh mesh = new TriangleMesh();
            mesh.getPoints().setAll(points, 0, 3 * pointCount);
            mesh.getTexCoords().setAll(0, 0);
            mesh.getFaces().setAll(faces, 0, 6 * faceCount);
            mesh.getFaceSmoothingGroups().setAll(smoothing, 0, faceCount);
            return mesh;
        }

        boolean isEmpty() {
            return faceCount == 0;
        }
    }

    /**
     * Joins a series of cross-sections into a smooth surface (a "loft"). All sections must
     * have the same number of points; each point is {x, y, z}.
     *
     * @param closedSection whether each cross-section is a closed ring
     * @param capEnds       whether to close the first and last section with a flat cap
     */
    static TriangleMesh loft(List<double[][]> sections, boolean closedSection, boolean capEnds) {
        Builder b = new Builder();
        int ringSize = sections.get(0).length;
        int[][] index = new int[sections.size()][ringSize];
        for (int s = 0; s < sections.size(); s++) {
            for (int k = 0; k < ringSize; k++) {
                double[] p = sections.get(s)[k];
                index[s][k] = b.vertex(p[0], p[1], p[2]);
            }
        }
        int segments = closedSection ? ringSize : ringSize - 1;
        for (int s = 0; s < sections.size() - 1; s++) {
            for (int k = 0; k < segments; k++) {
                int k2 = (k + 1) % ringSize;
                b.quad(index[s][k], index[s + 1][k], index[s + 1][k2], index[s][k2], 1);
            }
        }
        if (capEnds) {
            cap(b, sections.get(0), index[0], false);
            cap(b, sections.get(sections.size() - 1), index[sections.size() - 1], true);
        }
        return b.build();
    }

    private static void cap(Builder b, double[][] ring, int[] ringIndex, boolean reverse) {
        double cx = 0;
        double cy = 0;
        double cz = 0;
        for (double[] p : ring) {
            cx += p[0];
            cy += p[1];
            cz += p[2];
        }
        int centre = b.vertex(cx / ring.length, cy / ring.length, cz / ring.length);
        for (int k = 0; k < ring.length; k++) {
            int k2 = (k + 1) % ring.length;
            if (reverse) {
                b.triangle(centre, ringIndex[k2], ringIndex[k], 2);
            } else {
                b.triangle(centre, ringIndex[k], ringIndex[k2], 2);
            }
        }
    }

    /**
     * Adds a flat strip of constant width that follows a polyline, lying at height {@code y}
     * (negative = above the ground).
     */
    static void ribbon(Builder b, Polyline line, double width, double y) {
        Polyline left = line.offset(width / 2);
        Polyline right = line.offset(-width / 2);
        List<Point2> l = left.points();
        List<Point2> r = right.points();
        int n = l.size();
        int[] li = new int[n];
        int[] ri = new int[n];
        for (int i = 0; i < n; i++) {
            li[i] = b.vertex(l.get(i).x(), y, l.get(i).y());
            ri[i] = b.vertex(r.get(i).x(), y, r.get(i).y());
        }
        int segments = line.closed() ? n : n - 1;
        for (int i = 0; i < segments; i++) {
            int j = (i + 1) % n;
            b.quad(li[i], li[j], ri[j], ri[i], 0);
        }
    }

    /** Adds a flat rectangle on the ground from (x0, z0) to (x1, z1) at height {@code y}. */
    static void rectangle(Builder b, double x0, double z0, double x1, double z1, double y) {
        int a = b.vertex(x0, y, z0);
        int c = b.vertex(x1, y, z0);
        int d = b.vertex(x1, y, z1);
        int e = b.vertex(x0, y, z1);
        b.quad(a, e, d, c, 0);
    }

    /** Adds an axis-aligned box. */
    static void box(Builder b, double cx, double cy, double cz, double sx, double sy, double sz) {
        double x0 = cx - sx / 2;
        double x1 = cx + sx / 2;
        double y0 = cy - sy / 2;
        double y1 = cy + sy / 2;
        double z0 = cz - sz / 2;
        double z1 = cz + sz / 2;
        int p000 = b.vertex(x0, y0, z0);
        int p100 = b.vertex(x1, y0, z0);
        int p110 = b.vertex(x1, y1, z0);
        int p010 = b.vertex(x0, y1, z0);
        int p001 = b.vertex(x0, y0, z1);
        int p101 = b.vertex(x1, y0, z1);
        int p111 = b.vertex(x1, y1, z1);
        int p011 = b.vertex(x0, y1, z1);
        b.quad(p000, p100, p110, p010, 0);
        b.quad(p101, p001, p011, p111, 0);
        b.quad(p001, p000, p010, p011, 0);
        b.quad(p100, p101, p111, p110, 0);
        b.quad(p001, p101, p100, p000, 0);
        b.quad(p010, p110, p111, p011, 0);
    }
}
