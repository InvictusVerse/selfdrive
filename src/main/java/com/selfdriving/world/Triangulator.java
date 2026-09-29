package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a simple polygon (any shape, no holes) into triangles by ear clipping. Used for roofs,
 * parks and water. Map outlines are not always perfectly clean, so if clipping gets stuck the
 * rest is closed with a fan rather than failing.
 */
public final class Triangulator {

    private Triangulator() {
    }

    /**
     * @param outline polygon corners (either winding, first point not repeated)
     * @return triangle corner indices into {@code outline}, three per triangle, counter-clockwise
     */
    public static int[] triangulate(List<Point2> outline) {
        int n = outline.size();
        if (n < 3) {
            return new int[0];
        }
        List<Integer> remaining = new ArrayList<>(n);
        boolean ccw = Building.signedArea(outline) > 0;
        for (int i = 0; i < n; i++) {
            remaining.add(ccw ? i : n - 1 - i);
        }
        List<Integer> result = new ArrayList<>(3 * (n - 2));
        int guard = 0;
        int i = 0;
        while (remaining.size() > 3 && guard < n * n) {
            guard++;
            int m = remaining.size();
            int a = remaining.get((i + m - 1) % m);
            int b = remaining.get(i % m);
            int c = remaining.get((i + 1) % m);
            if (isEar(outline, remaining, a, b, c)) {
                result.add(a);
                result.add(b);
                result.add(c);
                remaining.remove(i % m);
                i = Math.max(0, i - 1);
            } else {
                i = (i + 1) % m;
            }
        }
        // What is left: a final triangle, or a fan if the outline was self-touching.
        for (int k = 1; k + 1 < remaining.size(); k++) {
            result.add(remaining.get(0));
            result.add(remaining.get(k));
            result.add(remaining.get(k + 1));
        }
        int[] out = new int[result.size()];
        for (int k = 0; k < out.length; k++) {
            out[k] = result.get(k);
        }
        return out;
    }

    private static boolean isEar(List<Point2> p, List<Integer> remaining, int a, int b, int c) {
        Point2 pa = p.get(a);
        Point2 pb = p.get(b);
        Point2 pc = p.get(c);
        double cross = (pb.x() - pa.x()) * (pc.y() - pa.y()) - (pb.y() - pa.y()) * (pc.x() - pa.x());
        if (cross <= 1e-9) {
            return false; // reflex or flat corner
        }
        for (int idx : remaining) {
            if (idx == a || idx == b || idx == c) {
                continue;
            }
            if (inside(p.get(idx), pa, pb, pc)) {
                return false;
            }
        }
        return true;
    }

    private static boolean inside(Point2 q, Point2 a, Point2 b, Point2 c) {
        double d1 = sign(q, a, b);
        double d2 = sign(q, b, c);
        double d3 = sign(q, c, a);
        boolean negative = d1 < 0 || d2 < 0 || d3 < 0;
        boolean positive = d1 > 0 || d2 > 0 || d3 > 0;
        return !(negative && positive);
    }

    private static double sign(Point2 p, Point2 a, Point2 b) {
        return (p.x() - b.x()) * (a.y() - b.y()) - (a.x() - b.x()) * (p.y() - b.y());
    }
}
