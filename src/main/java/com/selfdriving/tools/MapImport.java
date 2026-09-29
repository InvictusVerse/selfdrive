package com.selfdriving.tools;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.selfdriving.util.Json;

/**
 * Developer tool: downloads a real area from OpenStreetMap (via the Overpass API) and writes the
 * compact map file the app loads ({@code src/main/resources/com/selfdriving/world/maps/*.json}).
 * The app itself never goes online; the file is part of the source.
 *
 * <p>It keeps drivable roads (with name, class, one-way, lanes, speed limit), traffic signals,
 * building footprints with heights, parks and water, and named places, projected to metres
 * around the area's centre (x = east, y = north). Roads are cut at the area's edge.
 *
 * <p>Usage: {@code java -cp target/classes com.selfdriving.tools.MapImport <south> <west> <north>
 * <east> <output.json> "<display name>"}
 *
 * <p>Map data (c) OpenStreetMap contributors, available under the Open Database Licence (ODbL).
 */
public final class MapImport {

    private static final String ENDPOINT = "https://overpass-api.de/api/interpreter";
    private static final double EARTH_RADIUS = 6_371_008.8;
    private static final String ROAD_CLASSES = "^(trunk|primary|secondary|tertiary|unclassified|residential"
            + "|living_street|trunk_link|primary_link|secondary_link|tertiary_link)$";

    private final double south;
    private final double west;
    private final double north;
    private final double east;
    private final double lat0;
    private final double lon0;
    private final Map<Long, double[]> nodes = new HashMap<>();
    private final Map<Long, Map<String, Object>> nodeTags = new HashMap<>();
    private long nextSyntheticId = -1;

    private MapImport(double south, double west, double north, double east) {
        this.south = south;
        this.west = west;
        this.north = north;
        this.east = east;
        this.lat0 = (south + north) / 2;
        this.lon0 = (west + east) / 2;
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length == 2 && args[0].equals("--normalise")) {
            normalise(Path.of(args[1]));
            return;
        }
        if (args.length < 6) {
            System.err.println("Usage: MapImport <south> <west> <north> <east> <output.json> \"<display name>\"");
            System.exit(2);
        }
        MapImport importer = new MapImport(Double.parseDouble(args[0]), Double.parseDouble(args[1]),
                Double.parseDouble(args[2]), Double.parseDouble(args[3]));
        String json = importer.run(args[5]);
        Path out = Path.of(args[4]);
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.printf("Wrote %s (%.0f kB)%n", out, json.length() / 1024.0);
    }

    private String run(String displayName) throws IOException, InterruptedException {
        String bbox = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", south, west, north, east);
        String query = "[out:json][timeout:180];("
                + "way[highway~\"" + ROAD_CLASSES + "\"][area!=yes][access!~\"^(private|no)$\"](" + bbox + ");"
                + "way[building](" + bbox + ");"
                + "way[leisure~\"^(park|garden)$\"](" + bbox + ");"
                + "way[landuse~\"^(grass|recreation_ground|village_green)$\"](" + bbox + ");"
                + "way[natural=water](" + bbox + ");"
                + ");(._;>;);out body;"
                + "(node[name][~\"^(amenity|tourism|shop|railway|leisure|historic)$\"~\".\"](" + bbox + ");"
                + "way[name][~\"^(amenity|tourism|shop|leisure|historic)$\"~\".\"](" + bbox + "););out center;";
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofMinutes(4))
                .header("User-Agent", "selfdrive-map-import/1.0")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("data=" + URLEncoder.encode(query, StandardCharsets.UTF_8)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Overpass returned HTTP " + response.statusCode());
        }
        List<Object> elements = Json.array(Json.object(Json.parse(response.body())), "elements");

        List<Map<String, Object>> ways = new ArrayList<>();
        List<Map<String, Object>> pois = new ArrayList<>();
        for (Object o : elements) {
            Map<String, Object> e = Json.object(o);
            String type = Json.string(e, "type", "");
            Map<String, Object> tags = Json.object(e, "tags");
            if (type.equals("node") && e.containsKey("lat")) {
                long id = (long) (double) (Double) e.get("id");
                nodes.put(id, project((Double) e.get("lat"), (Double) e.get("lon")));
                if (!tags.isEmpty()) {
                    nodeTags.put(id, tags);
                }
                if (tags.containsKey("name") && isPlace(tags)) {
                    pois.add(e);
                }
            } else if (type.equals("way") && e.containsKey("center")) {
                pois.add(e);
            } else if (type.equals("way")) {
                ways.add(e);
            }
        }

        List<Object> roads = new ArrayList<>();
        List<Object> buildings = new ArrayList<>();
        List<Object> areas = new ArrayList<>();
        for (Map<String, Object> way : ways) {
            Map<String, Object> tags = Json.object(way, "tags");
            List<Long> refs = new ArrayList<>();
            for (Object r : Json.array(way, "nodes")) {
                refs.add((long) (double) (Double) r);
            }
            if (tags.containsKey("highway")) {
                for (List<Long> piece : clip(refs)) {
                    roads.add(road(way, tags, piece));
                }
            } else if (tags.containsKey("building")) {
                Map<String, Object> b = building(tags, refs);
                if (b != null) {
                    buildings.add(b);
                }
            } else if (refs.size() >= 4) {
                String kind = tags.containsKey("natural") ? "water" : "park";
                areas.add(Map.of("kind", kind, "pts", points(refs)));
            }
        }

        List<Object> signals = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> e : nodeTags.entrySet()) {
            if ("traffic_signals".equals(e.getValue().get("highway")) && inside(nodes.get(e.getKey()))) {
                signals.add(e.getKey());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", displayName);
        out.put("attribution", "Map data \u00A9 OpenStreetMap contributors, ODbL (openstreetmap.org/copyright)");
        out.put("extracted", LocalDate.now().toString());
        out.put("bbox", List.of(south, west, north, east));
        out.put("origin", List.of(lat0, lon0));
        out.put("roads", roads);
        out.put("signals", signals);
        out.put("buildings", buildings);
        out.put("areas", areas);
        out.put("places", places(pois));
        return JsonWriter.write(out);
    }

    /** Rewrites an existing map file with the current building rules (no download). */
    @SuppressWarnings("unchecked")
    private static void normalise(Path file) throws IOException {
        Map<String, Object> root = Json.object(Json.parse(Files.readString(file, StandardCharsets.UTF_8)));
        List<Object> buildings = new ArrayList<>();
        for (Object o : Json.array(root, "buildings")) {
            Map<String, Object> b = new LinkedHashMap<>(Json.object(o));
            b.remove("name");
            b.put("type", buildingUse(Json.string(b, "type", "yes")));
            buildings.add(b);
        }
        ((Map<String, Object>) root).put("buildings", buildings);
        Files.writeString(file, JsonWriter.write(root), StandardCharsets.UTF_8);
        System.out.println("Normalised " + file);
    }

    /** Only the broad use is kept: it sets a typical height when the map has none. */
    private static String buildingUse(String type) {
        return switch (type) {
            case "commercial", "retail", "office", "hotel", "supermarket" -> "commercial";
            case "apartments" -> "apartments";
            case "house", "residential", "detached", "terrace", "semidetached_house" -> "residential";
            case "church", "temple", "mosque", "cathedral", "chapel", "religious" -> "religious";
            default -> "other";
        };
    }

    // ---- roads ---------------------------------------------------------------------------

    private Map<String, Object> road(Map<String, Object> way, Map<String, Object> tags, List<Long> refs) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", way.get("id"));
        r.put("name", Json.string(tags, "name", Json.string(tags, "ref", "")));
        r.put("class", Json.string(tags, "highway", "residential"));
        String oneway = Json.string(tags, "oneway", "");
        boolean roundabout = "roundabout".equals(tags.get("junction")) || "circular".equals(tags.get("junction"));
        int dir = switch (oneway) {
            case "yes", "true", "1" -> 1;
            case "-1", "reverse" -> -1;
            default -> roundabout || Json.string(tags, "highway", "").equals("motorway") ? 1 : 0;
        };
        List<Long> ordered = new ArrayList<>(refs);
        if (dir == -1) {
            java.util.Collections.reverse(ordered);
            dir = 1;
        }
        r.put("oneway", dir == 1);
        if (roundabout) {
            r.put("roundabout", true);
        }
        putInt(r, "lanes", tags.get("lanes"));
        putInt(r, "lanesForward", tags.get("lanes:forward"));
        putInt(r, "lanesBackward", tags.get("lanes:backward"));
        putInt(r, "maxspeed", tags.get("maxspeed"));
        r.put("nodes", ordered);
        r.put("pts", points(ordered));
        return r;
    }

    /**
     * Cuts a way where it leaves the area; each cut gets a new node at the boundary, which the app
     * uses as a place where traffic enters and leaves the map.
     */
    private List<List<Long>> clip(List<Long> refs) {
        List<List<Long>> pieces = new ArrayList<>();
        List<Long> current = null;
        for (int i = 0; i < refs.size(); i++) {
            double[] p = nodes.get(refs.get(i));
            if (p == null) {
                continue;
            }
            boolean in = inside(p);
            if (in) {
                if (current == null) {
                    current = new ArrayList<>();
                    if (i > 0 && nodes.containsKey(refs.get(i - 1))) {
                        current.add(boundaryNode(nodes.get(refs.get(i - 1)), p));
                    }
                }
                current.add(refs.get(i));
            } else if (current != null) {
                current.add(boundaryNode(nodes.get(refs.get(i - 1)), p));
                if (current.size() >= 2) {
                    pieces.add(current);
                }
                current = null;
            }
        }
        if (current != null && current.size() >= 2) {
            pieces.add(current);
        }
        return pieces;
    }

    /** A new node where the segment from an inside point to an outside point crosses the boundary. */
    private long boundaryNode(double[] a, double[] b) {
        double[] inner = inside(a) ? a : b;
        double[] outer = inside(a) ? b : a;
        double[] box = boxMetres();
        double t = 1;
        for (int k = 0; k < 2; k++) {
            double d = outer[k] - inner[k];
            if (Math.abs(d) < 1e-9) {
                continue;
            }
            double limit = d > 0 ? box[k + 2] : box[k];
            t = Math.min(t, (limit - inner[k]) / d);
        }
        long id = nextSyntheticId--;
        nodes.put(id, new double[] {round(inner[0] + (outer[0] - inner[0]) * t), round(inner[1] + (outer[1] - inner[1]) * t)});
        return id;
    }

    // ---- buildings, places ----------------------------------------------------------------

    private Map<String, Object> building(Map<String, Object> tags, List<Long> refs) {
        if (refs.size() < 4) {
            return null;
        }
        double[] c = centroid(refs);
        if (c == null || !inside(c)) {
            return null;
        }
        Map<String, Object> b = new LinkedHashMap<>();
        Double height = number(tags.get("height"));
        Double levels = number(tags.get("building:levels"));
        if (height != null) {
            b.put("h", round(height));
        } else if (levels != null) {
            b.put("levels", levels);
        }
        b.put("type", buildingUse(Json.string(tags, "building", "yes")));
        b.put("pts", points(refs.subList(0, refs.size() - 1))); // closed ring: drop the repeated node
        return b;
    }

    private static boolean isPlace(Map<String, Object> tags) {
        for (String key : List.of("amenity", "tourism", "shop", "railway", "leisure", "historic")) {
            if (tags.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /** Named places worth driving to, most notable first (stations, malls, parks, landmarks). */
    private List<Object> places(List<Map<String, Object>> pois) {
        Map<String, Object[]> byName = new LinkedHashMap<>();
        for (Map<String, Object> e : pois) {
            Map<String, Object> tags = Json.object(e, "tags");
            String name = Json.string(tags, "name", "");
            if (name.isBlank()) {
                continue;
            }
            double[] p;
            if (e.containsKey("center")) {
                Map<String, Object> centre = Json.object(e, "center");
                p = project((Double) centre.get("lat"), (Double) centre.get("lon"));
            } else {
                p = project((Double) e.get("lat"), (Double) e.get("lon"));
            }
            if (!inside(p)) {
                continue;
            }
            int score = score(tags);
            Object[] existing = byName.get(name);
            if (existing == null || (int) existing[2] < score) {
                byName.put(name, new Object[] {name, p, score, category(tags)});
            }
        }
        List<Object[]> sorted = new ArrayList<>(byName.values());
        sorted.sort((a, b) -> Integer.compare((int) b[2], (int) a[2]));
        List<Object> result = new ArrayList<>();
        for (Object[] s : sorted) {
            if ((int) s[2] <= 0 || result.size() >= 40) {
                continue;
            }
            double[] p = (double[]) s[1];
            result.add(Map.of("name", s[0], "category", s[3], "x", p[0], "y", p[1]));
        }
        return result;
    }

    private static int score(Map<String, Object> tags) {
        String railway = Json.string(tags, "railway", "");
        String amenity = Json.string(tags, "amenity", "");
        String tourism = Json.string(tags, "tourism", "");
        String shop = Json.string(tags, "shop", "");
        String leisure = Json.string(tags, "leisure", "");
        if (railway.equals("station")) {
            return 100;
        }
        if (shop.equals("mall") || amenity.equals("cinema") || amenity.equals("theatre")) {
            return 90;
        }
        if (leisure.equals("park") || leisure.equals("stadium") || tags.containsKey("historic")) {
            return 80;
        }
        if (tourism.equals("hotel") || tourism.equals("museum") || tourism.equals("attraction")) {
            return 70;
        }
        if (amenity.equals("hospital") || amenity.equals("place_of_worship") || amenity.equals("bank")) {
            return 50;
        }
        if (amenity.equals("restaurant") || amenity.equals("cafe")) {
            return 20;
        }
        return 0;
    }

    private static String category(Map<String, Object> tags) {
        for (String key : List.of("railway", "shop", "leisure", "tourism", "historic", "amenity")) {
            if (tags.containsKey(key)) {
                return Json.string(tags, key, key);
            }
        }
        return "place";
    }

    // ---- geometry -----------------------------------------------------------------------

    private double[] project(double lat, double lon) {
        double x = Math.toRadians(lon - lon0) * EARTH_RADIUS * Math.cos(Math.toRadians(lat0));
        double y = Math.toRadians(lat - lat0) * EARTH_RADIUS;
        return new double[] {round(x), round(y)};
    }

    /** Area bounds in metres: {minX, minY, maxX, maxY}. */
    private double[] boxMetres() {
        double[] sw = project(south, west);
        double[] ne = project(north, east);
        return new double[] {sw[0], sw[1], ne[0], ne[1]};
    }

    private boolean inside(double[] p) {
        double[] box = boxMetres();
        return p[0] >= box[0] && p[0] <= box[2] && p[1] >= box[1] && p[1] <= box[3];
    }

    private double[] centroid(List<Long> refs) {
        double x = 0;
        double y = 0;
        int n = 0;
        for (long r : refs) {
            double[] p = nodes.get(r);
            if (p != null) {
                x += p[0];
                y += p[1];
                n++;
            }
        }
        return n == 0 ? null : new double[] {x / n, y / n};
    }

    private List<Double> points(List<Long> refs) {
        List<Double> pts = new ArrayList<>();
        for (long r : refs) {
            double[] p = nodes.get(r);
            if (p != null) {
                pts.add(p[0]);
                pts.add(p[1]);
            }
        }
        return pts;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static Double number(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String digits = s.replaceAll("[^0-9.]", " ").trim().split("\\s+")[0];
        try {
            return digits.isEmpty() ? null : Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putInt(Map<String, Object> target, String key, Object value) {
        Double n = number(value);
        if (n != null && n > 0) {
            target.put(key, (int) Math.round(n));
        }
    }
}
