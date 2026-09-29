package com.selfdriving.world.map;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.selfdriving.util.Json;
import com.selfdriving.world.Point2;

/**
 * A real map area as written by {@code tools.MapImport}: OpenStreetMap roads, signals,
 * buildings, parks and places, in metres (x east, y north). Loading can shift everything so the
 * area sits where the world wants it.
 *
 * @param name        display name of the area
 * @param attribution licence notice that must be shown with the map
 * @param bounds      area in world metres {minX, minY, maxX, maxY}
 */
public record MapData(String name, String attribution, double[] bounds, List<Road> roads, Set<Long> signals,
                      List<Building> buildings, List<Area> areas, List<Place> places) {

    /**
     * A road piece.
     *
     * @param nodes node ids, one per point (shared ids mean the roads meet there; negative ids
     *              are cuts at the edge of the area)
     */
    public record Road(long id, String name, String roadClass, boolean oneway, boolean roundabout, int lanes,
                       int lanesForward, int lanesBackward, int maxSpeedKmh, long[] nodes, List<Point2> points) {
    }

    /** A footprint; height and levels are NaN when the map does not say. */
    public record Building(double height, double levels, String type, String name, List<Point2> footprint) {
    }

    /** A park or water area. */
    public record Area(String kind, List<Point2> outline) {
    }

    /** A named place. */
    public record Place(String name, String category, Point2 position) {
    }

    /** Loads a map from the class path, shifted by (dx, dy). */
    public static MapData load(String resource, double dx, double dy) {
        try (InputStream in = MapData.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalArgumentException("Map not found: " + resource);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), dx, dy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static MapData parse(String text, double dx, double dy) {
        Map<String, Object> root = Json.object(Json.parse(text));
        List<Road> roads = new ArrayList<>();
        for (Object o : Json.array(root, "roads")) {
            Map<String, Object> r = Json.object(o);
            List<Object> ids = Json.array(r, "nodes");
            long[] nodes = new long[ids.size()];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = Math.round((Double) ids.get(i));
            }
            List<Point2> points = points(Json.array(r, "pts"), dx, dy, false);
            if (points.size() != nodes.length || points.size() < 2) {
                continue;
            }
            roads.add(new Road(Math.round(Json.number(r, "id", 0)), Json.string(r, "name", ""),
                    Json.string(r, "class", "residential"), Boolean.TRUE.equals(r.get("oneway")),
                    Boolean.TRUE.equals(r.get("roundabout")), Json.integer(r, "lanes", 0),
                    Json.integer(r, "lanesForward", 0), Json.integer(r, "lanesBackward", 0),
                    Json.integer(r, "maxspeed", 0), nodes, points));
        }
        Set<Long> signals = new HashSet<>();
        for (Object o : Json.array(root, "signals")) {
            signals.add(Math.round((Double) o));
        }
        List<Building> buildings = new ArrayList<>();
        for (Object o : Json.array(root, "buildings")) {
            Map<String, Object> b = Json.object(o);
            List<Point2> footprint = points(Json.array(b, "pts"), dx, dy);
            if (footprint.size() >= 3) {
                buildings.add(new Building(Json.number(b, "h", Double.NaN), Json.number(b, "levels", Double.NaN),
                        Json.string(b, "type", "yes"), Json.string(b, "name", ""), footprint));
            }
        }
        List<Area> areas = new ArrayList<>();
        for (Object o : Json.array(root, "areas")) {
            Map<String, Object> a = Json.object(o);
            List<Point2> outline = points(Json.array(a, "pts"), dx, dy);
            if (outline.size() >= 3) {
                areas.add(new Area(Json.string(a, "kind", "park"), outline));
            }
        }
        List<Place> places = new ArrayList<>();
        for (Object o : Json.array(root, "places")) {
            Map<String, Object> p = Json.object(o);
            places.add(new Place(Json.string(p, "name", ""), Json.string(p, "category", ""),
                    new Point2(Json.number(p, "x", 0) + dx, Json.number(p, "y", 0) + dy)));
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (Road r : roads) {
            for (Point2 p : r.points()) {
                minX = Math.min(minX, p.x());
                minY = Math.min(minY, p.y());
                maxX = Math.max(maxX, p.x());
                maxY = Math.max(maxY, p.y());
            }
        }
        return new MapData(Json.string(root, "name", "Map"), Json.string(root, "attribution", ""),
                new double[] {minX, minY, maxX, maxY}, List.copyOf(roads), Set.copyOf(signals),
                List.copyOf(buildings), List.copyOf(areas), List.copyOf(places));
    }

    private static List<Point2> points(List<Object> flat, double dx, double dy) {
        return points(flat, dx, dy, true);
    }

    private static List<Point2> points(List<Object> flat, double dx, double dy, boolean dropRepeats) {
        List<Point2> result = new ArrayList<>(flat.size() / 2);
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            Point2 p = new Point2((Double) flat.get(i) + dx, (Double) flat.get(i + 1) + dy);
            if (!dropRepeats || result.isEmpty() || result.get(result.size() - 1).distanceTo(p) > 0.05) {
                result.add(p);
            }
        }
        return result;
    }
}
