package com.selfdriving.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.selfdriving.world.map.MapData;

/**
 * Everything static in the simulated world: the proving ground, a real city area loaded from
 * OpenStreetMap data (Bengaluru, around MG Road), the road that joins them, the lane-level road
 * network, paint, buildings, parks and named places. Built once at start-up and shared
 * read-only by all threads.
 *
 * <p>Traffic keeps to the left, as in India.
 */
public final class World {

    /** The bundled city area. */
    public static final String CITY_MAP = "/com/selfdriving/world/maps/bengaluru-mg-road.json";

    /** Gap between the top of the proving ground and the southern edge of the city, m. */
    private static final double CITY_GAP = 260;

    private static final double LANE_WIDTH = 3.2;
    private static final int MAX_PLACES = 9;
    private static final double PLACE_SPACING = 150;
    private static final long PG_NODE = 9_000_000_000_000L;
    /** Buildings closer than this to a road's centre line are treated as drawn over the road, m. */
    private static final double MIN_CLEARANCE = 2.4;
    /** Free space needed either side of a lane or turn path centre (half a car plus room for its corners in bends), m. */
    private static final double LANE_CLEARANCE = 1.5;

    private final ProvingGround provingGround;
    private final ParkingArea parkingArea = new ParkingArea();
    private final MapData city;
    private final RoadNetwork network;
    private final List<Road> roads;
    private final List<Marking> markings;
    private final List<Building> buildings;
    private final Walls walls;
    private final List<MapData.Area> areas;
    private final List<Place> places;
    private final Pose cityStart;
    private long nextNode = PG_NODE + 10_000;

    public World() {
        this(CITY_MAP);
    }

    public World(String mapResource) {
        provingGround = new ProvingGround();
        MapData raw = MapData.load(mapResource, 0, 0);
        double offsetY = provingGround.roadBounds()[3] + CITY_GAP - raw.bounds()[1];
        double offsetX = -(raw.bounds()[0] + raw.bounds()[2]) / 2;
        city = MapData.load(mapResource, offsetX, offsetY);

        // Buildings first: roads are fitted between them.
        List<Building> solid = solidBuildings(createBuildings(city.buildings()), city.roads());
        Walls fittingWalls = new Walls(solid);

        List<RoadSource> sources = new ArrayList<>(provingGroundSources());
        List<RoadSource> citySources = new ArrayList<>();
        for (MapData.Road r : city.roads()) {
            citySources.add(fitted(source(r), fittingWalls));
        }
        sources.addAll(citySources);
        sources.add(connector(citySources));
        network = RoadNetwork.build(sources, city.signals());

        // Last check: no wall may reach into a lane or a turn path (map footprints sometimes
        // overlap the street); such outlines are dropped.
        java.util.Set<Integer> blocking = new java.util.HashSet<>();
        for (RoadNetwork.Link link : network.links()) {
            for (int lane = 0; lane < link.lanes(); lane++) {
                Polyline l = link.lane(lane);
                for (double s = 0; s <= l.length(); s += 2) {
                    Point2 p = l.pointAt(s);
                    blocking.addAll(fittingWalls.buildingsNear(p.x(), p.y(), LANE_CLEARANCE));
                }
            }
        }
        for (RoadNetwork.Connector c : network.connectors()) {
            Polyline l = c.path();
            for (double s = 0; s <= l.length(); s += 2) {
                Point2 p = l.pointAt(s);
                blocking.addAll(fittingWalls.buildingsNear(p.x(), p.y(), LANE_CLEARANCE));
            }
        }
        buildings = solid.stream().filter(b -> !blocking.contains(b.id())).toList();
        walls = new Walls(buildings);

        List<Road> allRoads = new ArrayList<>(provingGround.roads());
        for (RoadNetwork.Surface s : network.surfaces()) {
            double width = s.leftWidth() + s.rightWidth();
            allRoads.add(new Road("", s.centre().offset((s.leftWidth() - s.rightWidth()) / 2), width,
                    Math.max(1, (int) Math.round(width / LANE_WIDTH))));
        }
        roads = List.copyOf(allRoads);

        List<Marking> allMarkings = new ArrayList<>(provingGround.markings());
        allMarkings.addAll(RoadPainter.paint(network));
        allMarkings.addAll(parkingArea.markings());
        markings = List.copyOf(allMarkings);

        areas = city.areas();
        places = choosePlaces();
        cityStart = chooseStart();
    }

    // ---- sources ----------------------------------------------------------------------------

    /** The proving ground as network roads (drawn by the proving ground itself). */
    private List<RoadSource> provingGroundSources() {
        ProvingGround g = provingGround;
        List<RoadSource> list = new ArrayList<>();
        double circuitLane = g.circuitWidth() / 3;
        list.add(pgSource("Circuit", g.circuitEastHalf(), PG_NODE, PG_NODE + 1, true, 3, 0, circuitLane, 100));
        list.add(pgSource("Circuit", g.circuitWestHalf(), PG_NODE + 1, PG_NODE, true, 3, 0, circuitLane, 100));
        Polyline access = new Polyline(Polyline.straight(g.mainJunction(), g.skidpadEntrance(), 4), false);
        list.add(pgSource("Skidpad access", access, PG_NODE, PG_NODE + 2, false, 1, 1, 3.5, 40));
        return list;
    }

    private RoadSource pgSource(String name, Polyline line, long startNode, long endNode, boolean oneway,
                                int forward, int backward, double laneWidth, double kmh) {
        List<Point2> pts = line.points();
        long[] nodes = new long[pts.size()];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = nextNode++;
        }
        nodes[0] = startNode;
        nodes[nodes.length - 1] = endNode;
        return new RoadSource(name, 5, pts, nodes, oneway, forward, backward, laneWidth, kmh / 3.6, false, false);
    }

    /** A map road with Indian urban defaults for anything the map leaves out. */
    private static RoadSource source(MapData.Road r) {
        String cls = r.roadClass();
        boolean link = cls.endsWith("_link");
        String base = link ? cls.substring(0, cls.length() - 5) : cls;
        int rank = switch (base) {
            case "trunk" -> 6;
            case "primary" -> 5;
            case "secondary" -> 4;
            case "tertiary" -> 3;
            case "living_street" -> 1;
            default -> 2;
        };
        if (link) {
            rank = Math.max(2, rank - 1);
        }
        int forward;
        int backward;
        if (r.oneway()) {
            forward = r.lanes() > 0 ? r.lanes() : switch (base) {
                case "trunk", "primary" -> link ? 1 : 3;
                case "secondary", "tertiary" -> link ? 1 : 2;
                default -> 1;
            };
            backward = 0;
        } else if (r.lanesForward() > 0 && r.lanesBackward() > 0) {
            forward = r.lanesForward();
            backward = r.lanesBackward();
        } else if (r.lanes() >= 2) {
            forward = (r.lanes() + 1) / 2;
            backward = r.lanes() - forward;
        } else {
            forward = backward = switch (base) {
                case "trunk", "primary", "secondary" -> link ? 1 : 2;
                default -> 1;
            };
        }
        forward = Math.max(1, Math.min(4, forward));
        backward = Math.max(r.oneway() ? 0 : 1, Math.min(4, backward));
        double kmh = r.maxSpeedKmh() > 0 ? r.maxSpeedKmh() : switch (base) {
            case "trunk" -> 60;
            case "primary" -> 50;
            case "secondary", "tertiary" -> 40;
            case "living_street" -> 20;
            default -> 30;
        };
        if (link) {
            kmh = Math.min(kmh, 30);
        }
        double laneWidth = base.equals("living_street") ? 2.8 : LANE_WIDTH;
        return new RoadSource(r.name().isBlank() ? "Unnamed road" : r.name(), rank, r.points(), r.nodes(), r.oneway(),
                forward, backward, laneWidth, kmh / 3.6, r.roundabout(), true);
    }

    /**
     * The road from the proving ground's north junction to the nearest two-way city road that
     * leaves the southern edge of the map.
     */
    private RoadSource connector(List<RoadSource> citySources) {
        Point2 start = provingGround.topJunction();
        RoadSource best = null;
        int bestIndex = 0;
        double bestScore = Double.MAX_VALUE;
        double south = city.bounds()[1];
        for (RoadSource s : citySources) {
            if (s.oneway() || s.rank() < 2) {
                continue;
            }
            for (int end : new int[] {0, s.nodes().length - 1}) {
                if (s.nodes()[end] >= 0) {
                    continue; // only cuts at the map edge
                }
                Point2 p = s.points().get(end);
                if (p.y() - south > 5) {
                    continue;
                }
                double score = Math.abs(p.x() - start.x()) - s.rank() * 250;
                if (score < bestScore) {
                    bestScore = score;
                    best = s;
                    bestIndex = end;
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("No two-way road leaves the south edge of the map");
        }
        Point2 end = best.points().get(bestIndex);
        Point2 inward = best.points().get(bestIndex == 0 ? 1 : best.points().size() - 2);
        double h = Math.atan2(inward.y() - end.y(), inward.x() - end.x());
        // Straight north, then a smooth curve into the city road.
        Point2 bendStart = new Point2(start.x(), Math.max(start.y() + 40, end.y() - 120));
        List<Point2> pts = new ArrayList<>(Polyline.straight(start, bendStart, 6));
        pts.remove(pts.size() - 1);
        double k = bendStart.distanceTo(end) * 0.4;
        Point2 c1 = new Point2(bendStart.x(), bendStart.y() + k);
        Point2 c2 = new Point2(end.x() - Math.cos(h) * k, end.y() - Math.sin(h) * k);
        for (int i = 0; i <= 30; i++) {
            double t = i / 30.0;
            double u = 1 - t;
            pts.add(new Point2(u * u * u * bendStart.x() + 3 * u * u * t * c1.x() + 3 * u * t * t * c2.x() + t * t * t * end.x(),
                    u * u * u * bendStart.y() + 3 * u * u * t * c1.y() + 3 * u * t * t * c2.y() + t * t * t * end.y()));
        }
        long[] nodes = new long[pts.size()];
        for (int i = 0; i < nodes.length; i++) {
            nodes[i] = nextNode++;
        }
        nodes[0] = PG_NODE + 1;
        nodes[nodes.length - 1] = best.nodes()[bestIndex];
        return new RoadSource("Proving Ground Road", 3, pts, nodes, false, 1, 1, 3.5, 60 / 3.6, false, true);
    }

    // ---- fitting roads between buildings -----------------------------------------------------

    /**
     * Drops "buildings" that a road runs through: in map data these are canopies, arcades,
     * elevated stations and bridges over the street, which a car drives under.
     */
    private static List<Building> solidBuildings(List<Building> all, List<MapData.Road> roads) {
        java.util.Map<Long, List<Building>> grid = new java.util.HashMap<>();
        for (Building b : all) {
            double[] box = box(b.footprint());
            for (int x = (int) Math.floor(box[0] / 50); x <= (int) Math.floor(box[2] / 50); x++) {
                for (int y = (int) Math.floor(box[1] / 50); y <= (int) Math.floor(box[3] / 50); y++) {
                    grid.computeIfAbsent(((long) x << 32) ^ (y & 0xffffffffL), k -> new ArrayList<>()).add(b);
                }
            }
        }
        java.util.Set<Integer> over = new java.util.HashSet<>();
        for (MapData.Road r : roads) {
            Polyline line = new Polyline(r.points(), false);
            for (double s = 0; s <= line.length(); s += 2.0) {
                Point2 p = line.pointAt(s);
                long key = ((long) (int) Math.floor(p.x() / 50) << 32) ^ ((int) Math.floor(p.y() / 50) & 0xffffffffL);
                for (Building b : grid.getOrDefault(key, List.of())) {
                    // On the road, or so close to its centre line that no car could pass: drawn over
                    // the street in the map (arcade, canopy, gate), not a solid wall.
                    if (!over.contains(b.id()) && (contains(b.footprint(), p) || distance(b.footprint(), p) < MIN_CLEARANCE)) {
                        over.add(b.id());
                    }
                }
            }
        }
        List<Building> solid = new ArrayList<>();
        for (Building b : all) {
            if (!over.contains(b.id())) {
                solid.add(b);
            }
        }
        return List.copyOf(solid);
    }

    /**
     * Fits a road between the buildings along it: measures the free width on each side of the
     * centre line and reduces the number (and if needed the width) of lanes so the lanes stay
     * clear of the walls. Map lane counts are often missing; this keeps narrow streets narrow.
     */
    private static RoadSource fitted(RoadSource s, Walls walls) {
        Polyline line = new Polyline(s.points(), false);
        double length = line.length();
        List<Double> lefts = new ArrayList<>();
        List<Double> rights = new ArrayList<>();
        for (double at = length * 0.1; at <= length * 0.9; at += 6) {
            Point2 p = line.pointAt(at);
            double h = line.headingAt(at);
            double nx = -Math.sin(h);
            double ny = Math.cos(h);
            Walls.Hit left = walls.cast(p.x(), p.y(), nx, ny, 25);
            Walls.Hit right = walls.cast(p.x(), p.y(), -nx, -ny, 25);
            lefts.add(left == null ? 25 : left.distance());
            rights.add(right == null ? 25 : right.distance());
        }
        if (lefts.isEmpty()) {
            return s;
        }
        double left = quantile(lefts, 0.1);
        double right = quantile(rights, 0.1);
        double margin = 0.7; // footpath / kerb space left beside the outer lane
        double w = s.laneWidth();
        int forward;
        int backward;
        if (s.oneway()) {
            double free = 2 * Math.min(left, right) - 2 * margin;
            forward = Math.max(1, Math.min(s.lanesForward(), (int) Math.floor(free / w)));
            backward = 0;
            if (free < forward * w) {
                w = Math.max(2.7, free / forward);
            }
        } else {
            forward = Math.max(1, Math.min(s.lanesForward(), (int) Math.floor((left - margin) / w)));
            backward = Math.max(1, Math.min(s.lanesBackward(), (int) Math.floor((right - margin) / w)));
            double tightest = Math.min((left - margin) / forward, (right - margin) / backward);
            if (tightest < w) {
                w = Math.max(2.7, tightest);
            }
        }
        if (forward == s.lanesForward() && backward == s.lanesBackward() && w == s.laneWidth()) {
            return s;
        }
        return new RoadSource(s.name(), s.rank(), s.points(), s.nodes(), s.oneway(), forward, backward, w,
                s.speedLimit(), s.roundabout(), s.rendered());
    }

    private static double quantile(List<Double> values, double q) {
        List<Double> sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        return sorted.get((int) Math.floor(q * (sorted.size() - 1)));
    }

    private static double[] box(List<Point2> outline) {
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (Point2 p : outline) {
            b[0] = Math.min(b[0], p.x());
            b[1] = Math.min(b[1], p.y());
            b[2] = Math.max(b[2], p.x());
            b[3] = Math.max(b[3], p.y());
        }
        return b;
    }

    /** Distance from a point to the outline of a polygon, m. */
    private static double distance(List<Point2> outline, Point2 p) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < outline.size(); i++) {
            Point2 a = outline.get(i);
            Point2 b = outline.get((i + 1) % outline.size());
            double sx = b.x() - a.x();
            double sy = b.y() - a.y();
            double len2 = sx * sx + sy * sy;
            double t = len2 < 1e-12 ? 0 : Math.max(0, Math.min(1, ((p.x() - a.x()) * sx + (p.y() - a.y()) * sy) / len2));
            best = Math.min(best, Math.hypot(p.x() - a.x() - sx * t, p.y() - a.y() - sy * t));
        }
        return best;
    }

    /** Point-in-polygon (even-odd rule). */
    static boolean contains(List<Point2> outline, Point2 p) {
        boolean inside = false;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            Point2 a = outline.get(i);
            Point2 b = outline.get(j);
            if ((a.y() > p.y()) != (b.y() > p.y())
                    && p.x() < (b.x() - a.x()) * (p.y() - a.y()) / (b.y() - a.y()) + a.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    // ---- buildings, places, start --------------------------------------------------------

    private static List<Building> createBuildings(List<MapData.Building> raw) {
        List<Building> result = new ArrayList<>();
        int id = 1000;
        for (MapData.Building b : raw) {
            List<Point2> outline = new ArrayList<>(b.footprint());
            if (Math.abs(Building.signedArea(outline)) < 8) {
                continue; // sheds and slivers
            }
            if (Building.signedArea(outline) < 0) {
                java.util.Collections.reverse(outline);
            }
            result.add(new Building(id, outline, height(b, id), b.name()));
            id++;
        }
        return List.copyOf(result);
    }

    /** Height from the map, or from the floor count, or a typical value for the building type. */
    private static double height(MapData.Building b, int id) {
        if (!Double.isNaN(b.height())) {
            return Math.max(3, b.height());
        }
        double levels = b.levels();
        if (Double.isNaN(levels)) {
            int hash = Math.floorMod(id * 2654435761L, 1000) % 7;
            levels = switch (b.type()) {
                case "commercial" -> 4 + hash;
                case "apartments" -> 5 + hash;
                case "residential" -> 2 + hash % 2;
                case "religious" -> 4;
                default -> 2 + hash % 4;
            };
        }
        return Math.max(1, levels) * 3.2 + 1.0;
    }

    private List<Place> choosePlaces() {
        List<Place> chosen = new ArrayList<>();
        for (MapData.Place p : city.places()) {
            if (chosen.size() >= MAX_PLACES) {
                break;
            }
            boolean crowded = chosen.stream().anyMatch(c -> c.location().distanceTo(p.position()) < PLACE_SPACING);
            if (crowded || network.locate(p.position().x(), p.position().y(), Double.NaN, 70, Math.PI) == null
                    && nearestKerb(p.position()) == null) {
                continue;
            }
            String name = p.category().equals("station") && !p.name().toLowerCase(Locale.ROOT).contains("metro")
                    ? p.name() + " Metro" : p.name();
            chosen.add(new Place(name, p.position()));
        }
        chosen.add(new Place("Proving Ground", provingGround.mainJunction()));
        chosen.add(new Place("Car park (proving ground)", new Point2(-1.75, 28)));
        return List.copyOf(chosen);
    }

    /**
     * Where a car should stop for a place: the left-hand lane of the nearest suitable road, a
     * little way from its ends.
     */
    public RoadNetwork.Position nearestKerb(Point2 point) {
        RoadNetwork.Position best = null;
        double bestDistance = 120;
        for (RoadNetwork.Link link : network.links()) {
            if (link.length() < 25 || network.junction(link.to()).isBoundary()
                    || network.junction(link.from()).isBoundary() || link.name().equals("Circuit")) {
                continue;
            }
            Polyline lane = link.lane(0);
            Polyline.Projection p = lane.project(point.x(), point.y());
            if (p.distance() < bestDistance) {
                double arc = Math.max(8, Math.min(link.length() - 12, p.arc()));
                bestDistance = p.distance();
                best = new RoadNetwork.Position(link, 0, arc, link.laneOffset(0), p.distance());
            }
        }
        return best;
    }

    /** City start: the left lane of a main road, near the middle of the map. */
    private Pose chooseStart() {
        RoadNetwork.Link best = null;
        double bestScore = -Double.MAX_VALUE;
        double cx = (city.bounds()[0] + city.bounds()[2]) / 2;
        double cy = (city.bounds()[1] + city.bounds()[3]) / 2;
        for (RoadNetwork.Link link : network.links()) {
            if (!link.isRendered() || link.length() < 90 || link.name().equals("Proving Ground Road")) {
                continue;
            }
            Point2 mid = link.centre().pointAt(link.length() / 2);
            double score = link.rank() * 200 - mid.distanceTo(new Point2(cx, cy)) + Math.min(link.length(), 250);
            if (score > bestScore) {
                bestScore = score;
                best = link;
            }
        }
        if (best == null) {
            return provingGround.start();
        }
        Polyline lane = best.lane(0);
        double arc = Math.min(25, lane.length() / 4);
        Point2 p = lane.pointAt(arc);
        return new Pose(p.x(), p.y(), lane.headingAt(arc));
    }

    // ---- accessors ----------------------------------------------------------------------------

    /** The car park beside the proving ground's access road. */
    public ParkingArea parkingArea() {
        return parkingArea;
    }

    public ProvingGround provingGround() {
        return provingGround;
    }

    /** The city map as loaded (name, attribution, raw data). */
    public MapData city() {
        return city;
    }

    /** Lanes, junctions, turns and signals. */
    public RoadNetwork network() {
        return network;
    }

    /** Asphalt to draw. */
    public List<Road> roads() {
        return roads;
    }

    public List<Marking> markings() {
        return markings;
    }

    public List<Building> buildings() {
        return buildings;
    }

    /** Building walls, for sensors and collisions. */
    public Walls walls() {
        return walls;
    }

    /** Parks and water. */
    public List<MapData.Area> areas() {
        return areas;
    }

    public List<Place> places() {
        return places;
    }

    /** Where the car starts: in the city. */
    public Pose start() {
        return cityStart;
    }

    /** Start line of the proving ground (for road tests). */
    public Pose provingGroundStart() {
        return provingGround.start();
    }

    /** Licence notice for the map data. */
    public String attribution() {
        return city.attribution();
    }

    /** Bounding box of everything: {minX, minY, maxX, maxY}. */
    public double[] bounds() {
        double[] pg = provingGround.roadBounds();
        double[] c = city.bounds();
        return new double[] {Math.min(pg[0], c[0]) - 20, Math.min(pg[1], c[1]) - 20, Math.max(pg[2], c[2]) + 20,
                Math.max(pg[3], c[3]) + 20};
    }
}
