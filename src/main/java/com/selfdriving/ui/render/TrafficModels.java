package com.selfdriving.ui.render;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javafx.scene.shape.TriangleMesh;

import com.selfdriving.world.Obstacle;

/**
 * Simple, clean shapes for other road users, in the style of an autopilot display: a light
 * grey body, dark glass and dark wheels. Meshes are built once per vehicle type and shared by
 * every vehicle of that type, so a busy street costs little to draw.
 *
 * <p>Local frame as for the car: X forward, Y down, Z left, origin on the ground at the centre.
 */
final class TrafficModels {

    /** The three meshes of one vehicle type. */
    record Shape(TriangleMesh body, TriangleMesh glass, TriangleMesh wheels, double length, double width,
                 double lampHeight) {
    }

    private final Map<Obstacle.Kind, Shape> byKind = new EnumMap<>(Obstacle.Kind.class);
    private Shape suv;

    /** The shape for a vehicle of this kind and length. */
    Shape shape(Obstacle.Kind kind, double length, double width) {
        if (kind == Obstacle.Kind.CAR && length > 4.55) {
            if (suv == null) {
                suv = car(length, width, 1.78, 0.55);
            }
            return suv;
        }
        return byKind.computeIfAbsent(kind, k -> switch (k) {
            case BUS -> bus(length, width);
            case TRUCK -> truck(length, width);
            case AUTO_RICKSHAW -> autoRickshaw(length, width);
            case MOTORBIKE -> motorbike(length, width);
            default -> car(length, width, 1.46, 0.48);
        });
    }

    // ---- shapes --------------------------------------------------------------------------------

    /** A car: rounded lower body and a glass cabin. */
    private static Shape car(double length, double width, double roof, double cabinShare) {
        double half = length / 2;
        double belt = roof * 0.62;
        List<double[][]> lower = new ArrayList<>();
        int stations = 14;
        for (int s = 0; s < stations; s++) {
            double t = 0.5 - 0.5 * Math.cos(Math.PI * s / (stations - 1));
            double x = -half + length * t;
            double end = Math.min(1, Math.min(t, 1 - t) * 6);
            double top = belt * (0.78 + 0.22 * end);
            double bottom = 0.22 + 0.12 * (1 - end);
            double w = width / 2 * (0.82 + 0.18 * end);
            lower.add(ring(x, bottom, top, w, 14, 4.5));
        }
        List<double[][]> cabin = new ArrayList<>();
        double cabinStart = -half * cabinShare - 0.2 * half;
        double cabinEnd = half * cabinShare * 0.8;
        for (int s = 0; s < 8; s++) {
            double t = (double) s / 7;
            double x = cabinStart + (cabinEnd - cabinStart) * t;
            double rise = Math.sin(Math.PI * Math.min(1, t * 1.25)) * 0.35 + 0.65;
            double top = belt + (roof - belt) * Math.min(1, rise);
            double w = width / 2 * 0.86 - (top - belt) * 0.25;
            cabin.add(ring(x, belt - 0.02, top, w, 10, 3.0));
        }
        return new Shape(MeshFactory.loft(lower, true, true), MeshFactory.loft(cabin, true, true),
                wheels(length * 0.31, width / 2 - 0.12, 0.31, 0.2, 2), length, width, belt * 0.9);
    }

    private static Shape bus(double length, double width) {
        MeshFactory.Builder body = new MeshFactory.Builder();
        MeshFactory.box(body, 0, -1.65, 0, length, 2.7, width);
        MeshFactory.box(body, 0, -3.05, 0, length * 0.97, 0.1, width * 0.96);
        MeshFactory.Builder glass = new MeshFactory.Builder();
        MeshFactory.box(glass, 0.3, -2.25, 0, length * 0.92, 0.95, width + 0.02);
        MeshFactory.box(glass, length / 2, -2.1, 0, 0.04, 1.4, width * 0.86);
        return new Shape(body.build(), glass.build(), wheels(length * 0.34, width / 2 - 0.2, 0.5, 0.3, 2), length,
                width, 0.9);
    }

    private static Shape truck(double length, double width) {
        MeshFactory.Builder body = new MeshFactory.Builder();
        double cab = 2.2;
        MeshFactory.box(body, length / 2 - cab / 2, -1.5, 0, cab, 2.3, width * 0.96);
        double box = length - cab - 0.3;
        MeshFactory.box(body, -length / 2 + box / 2, -1.95, 0, box, 2.6, width);
        MeshFactory.Builder glass = new MeshFactory.Builder();
        MeshFactory.box(glass, length / 2 - 0.02, -2.05, 0, 0.06, 0.8, width * 0.86);
        return new Shape(body.build(), glass.build(), wheels(length * 0.36, width / 2 - 0.2, 0.5, 0.3, 2), length,
                width, 0.9);
    }

    /** Three-wheeler: narrow nose, open body, canvas roof. */
    private static Shape autoRickshaw(double length, double width) {
        MeshFactory.Builder body = new MeshFactory.Builder();
        MeshFactory.box(body, -0.2, -0.65, 0, length * 0.72, 0.7, width);
        MeshFactory.box(body, length * 0.36, -0.75, 0, length * 0.2, 0.9, width * 0.45);
        MeshFactory.box(body, -0.25, -1.72, 0, length * 0.8, 0.08, width * 1.02);
        for (double z : new double[] {width / 2 - 0.05, -width / 2 + 0.05}) {
            MeshFactory.box(body, length * 0.12, -1.3, z, 0.05, 0.85, 0.05);
            MeshFactory.box(body, -length * 0.6, -1.3, z, 0.05, 0.85, 0.05);
        }
        MeshFactory.Builder glass = new MeshFactory.Builder();
        MeshFactory.box(glass, length * 0.2, -1.35, 0, 0.04, 0.6, width * 0.8);
        MeshFactory.Builder wheels = new MeshFactory.Builder();
        MeshFactory.box(wheels, length * 0.36, -0.22, 0, 0.44, 0.44, 0.14);
        for (double z : new double[] {width / 2 - 0.1, -width / 2 + 0.1}) {
            MeshFactory.box(wheels, -length * 0.3, -0.22, z, 0.44, 0.44, 0.14);
        }
        return new Shape(body.build(), glass.build(), wheels.build(), length, width, 0.7);
    }

    /** Motorbike with a rider. */
    private static Shape motorbike(double length, double width) {
        MeshFactory.Builder body = new MeshFactory.Builder();
        MeshFactory.box(body, 0, -0.62, 0, length * 0.75, 0.42, 0.34);
        MeshFactory.box(body, -0.05, -1.12, 0, 0.36, 0.62, 0.42); // rider's body
        MeshFactory.Builder glass = new MeshFactory.Builder();
        MeshFactory.box(glass, -0.02, -1.58, 0, 0.26, 0.26, 0.26); // helmet
        MeshFactory.Builder wheels = new MeshFactory.Builder();
        MeshFactory.box(wheels, length * 0.36, -0.3, 0, 0.6, 0.6, 0.12);
        MeshFactory.box(wheels, -length * 0.36, -0.3, 0, 0.6, 0.6, 0.12);
        return new Shape(body.build(), glass.build(), wheels.build(), length, width, 0.8);
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** A rounded-box cross-section at x from {@code bottom} to {@code top} height. */
    private static double[][] ring(double x, double bottom, double top, double halfWidth, int points, double n) {
        double[][] ring = new double[points][];
        double mid = (top + bottom) / 2;
        double halfHeight = (top - bottom) / 2;
        for (int k = 0; k < points; k++) {
            double a = 2 * Math.PI * k / points;
            double z = halfWidth * superellipse(Math.cos(a), n);
            double h = mid + halfHeight * superellipse(Math.sin(a), n);
            ring[k] = new double[] {x, -h, z};
        }
        return ring;
    }

    private static double superellipse(double value, double n) {
        return Math.signum(value) * Math.pow(Math.abs(value), 2 / n);
    }

    private static TriangleMesh wheels(double axleX, double sideZ, double radius, double width, int axles) {
        MeshFactory.Builder b = new MeshFactory.Builder();
        for (double x : new double[] {axleX, -axleX}) {
            for (double z : new double[] {sideZ, -sideZ}) {
                MeshFactory.box(b, x, -radius, z, radius * 1.9, radius * 1.9, width);
            }
        }
        return b.build();
    }
}
