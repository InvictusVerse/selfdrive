package com.selfdriving.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Road paint for the network, in the style of Indian urban roads (IRC 35): white edge lines,
 * broken white lines between lanes going the same way, a yellow centre line on main two-way
 * roads (broken white on minor ones), and stop lines with zebra crossings at signals.
 * Lines stop at the edge of each junction.
 */
final class RoadPainter {

    private static final double EDGE = 0.15;
    private static final double LANE = 0.12;
    private static final double INSET = 0.3;

    private RoadPainter() {
    }

    static List<Marking> paint(RoadNetwork network) {
        List<Marking> out = new ArrayList<>();
        Set<Integer> centresDone = new HashSet<>();
        for (RoadNetwork.Link link : network.links()) {
            if (!link.isRendered() || link.length() < 2) {
                continue;
            }
            Polyline c = link.centre();
            double w = link.laneWidth();
            // Kerb-side edge line.
            out.add(new Marking(c.offset(link.leftEdge() - INSET), EDGE, Marking.Kind.EDGE));
            // Lanes going the same way.
            for (int lane = 0; lane < link.lanes() - 1; lane++) {
                double offset = link.laneOffset(lane) - w / 2;
                for (Polyline dash : c.offset(offset).dashes(3, 5)) {
                    out.add(new Marking(dash, LANE, Marking.Kind.LANE));
                }
            }
            if (link.reverseId() < 0) {
                out.add(new Marking(c.offset(link.rightEdge() + INSET), EDGE, Marking.Kind.EDGE));
            } else if (centresDone.add(link.piece())) {
                if (link.rank() >= 4) {
                    out.add(new Marking(c.offset(0.12), LANE, Marking.Kind.CENTRE));
                    out.add(new Marking(c.offset(-0.12), LANE, Marking.Kind.CENTRE));
                } else {
                    for (Polyline dash : c.dashes(3, 5)) {
                        out.add(new Marking(dash, LANE, Marking.Kind.LANE));
                    }
                }
            }
            if (network.junction(link.to()).isSignalised() && link.length() > 12) {
                crossing(out, network, link);
            }
        }
        return out;
    }

    /** Stop line across this link's lanes and a zebra crossing across the whole road ahead of it. */
    private static void crossing(List<Marking> out, RoadNetwork network, RoadNetwork.Link link) {
        Polyline c = link.centre();
        double stop = link.stopArc(true);
        Point2 at = c.pointAt(stop);
        double h = c.headingAt(stop);
        double nx = -Math.sin(h);
        double ny = Math.cos(h);
        double left = link.leftEdge() - INSET;
        double right = link.rightEdge() + (link.reverseId() < 0 ? INSET : 0.2);
        out.add(new Marking(Polyline.of(false, new Point2(at.x() + nx * right, at.y() + ny * right),
                new Point2(at.x() + nx * left, at.y() + ny * left)), 0.4, Marking.Kind.EDGE));

        double far = right;
        if (link.reverseId() >= 0) {
            RoadNetwork.Link back = network.link(link.reverseId());
            far = -back.leftEdge() + INSET;
        }
        double from = stop + 1.0;
        double to = Math.min(link.length() - 0.2, stop + 4.5);
        if (to - from < 1.5) {
            return;
        }
        for (double lateral = far + 0.4; lateral < left - 0.2; lateral += 1.0) {
            Point2 a = c.pointAt(from);
            Point2 b = c.pointAt(to);
            double ha = c.headingAt(from);
            double hb = c.headingAt(to);
            out.add(new Marking(Polyline.of(false,
                    new Point2(a.x() - Math.sin(ha) * lateral, a.y() + Math.cos(ha) * lateral),
                    new Point2(b.x() - Math.sin(hb) * lateral, b.y() + Math.cos(hb) * lateral)), 0.5, Marking.Kind.ZEBRA));
        }
    }
}
