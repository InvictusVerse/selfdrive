package com.selfdriving.ui.render;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.transform.Translate;

import com.selfdriving.physics.VehicleParams;
import com.selfdriving.ui.render.gltf.GltfModel;
import com.selfdriving.vehicle.LightState;

/**
 * Turns a car model file (binary glTF, optionally inside a .zip) into a {@link CarVisual}.
 *
 * <p>The model is placed in the car frame using its wheels: nodes named like {@code tire FL},
 * {@code tire RR} give the axles, so the front, left and up directions come from the model
 * itself. It is scaled uniformly so its wheelbase matches the simulated car, which keeps the
 * wheels over the physics contact points.
 *
 * <p>Lamps are found by name. Models made for configurators switch lamps by swapping two
 * versions of a part ({@code "X"} and {@code "X Lights_On"} or {@code "X Turn_Signal"});
 * those pairs become headlights, tail lights, the high-mounted brake light and indicators
 * (split into left and right by position).
 *
 * <p>Runs on a background thread; the result is attached on the JavaFX thread.
 */
public final class ImportedCar {

    private static final Pattern WHEEL = Pattern.compile("(?i)\\b(?:tire|tyre|wheel)[ _-]*(FL|FR|RL|RR)\\b");
    /**
     * Materials of parts that cannot be seen from outside (the cabin behind tinted glass, and
     * door seals inside the shut lines). Skipping them roughly halves the triangle count.
     */
    private static final Pattern HIDDEN_MATERIAL = Pattern.compile(
            "(?i)^(rubber|seat|leather|plastic_leather|pelt|floor|carpet|console|aircon|seatbelt|stitch|fillar"
                    + "|door ?(button|lock|speaker)|dial|navi|display|wheelbutton|steering wheel|roomlamp|rooflamp"
                    + "|gear|drive|press|accel|brake|stop|start|charge|indicator)\\b.*");
    private static final String LIGHTS_ON = " Lights_On";
    private static final String TURN_SIGNAL = " Turn_Signal";

    private enum Role { STATIC, HEAD, TAIL, BRAKE, INDICATOR }

    private ImportedCar() {
    }

    /** Model files in the folder, sorted by name (.glb, or .zip containing one). */
    public static List<Path> findModels(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(".glb") || n.endsWith(".zip");
            }).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Reads and builds a model. */
    public static CarVisual load(Path file, VehicleParams params) throws IOException {
        byte[] glb = readGlb(file);
        GltfModel model = GltfModel.readGlb(glb);
        String name = file.getFileName().toString().replaceFirst("\\.(zip|glb)$", "");
        return new Builder(model, params, name).build();
    }

    private static byte[] readGlb(Path file) throws IOException {
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
            return Files.readAllBytes(file);
        }
        try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".glb")) {
                    return zip.readAllBytes();
                }
            }
        }
        throw new IOException("No .glb model inside " + file.getFileName());
    }

    /** One mesh primitive in the scene root's frame, with its role. */
    private record Part(Role role, boolean on, int wheel, int material, float[] positions, float[] normals,
                        float[] uvs, int[] indices) {
    }

    /** Collects triangles that share one material and one switchable group into one mesh. */
    private static final class Accumulator {
        private float[] points = new float[3 * 1024];
        private float[] normals = new float[3 * 1024];
        private float[] uvs = new float[2 * 1024];
        private int[] faces = new int[9 * 1024];
        private int vertexCount;
        private int faceCount;

        void add(float[] p, float[] n, float[] uv, int[] tri, int triangle) {
            ensure(3);
            int base = vertexCount;
            for (int k = 0; k < 3; k++) {
                int v = tri[3 * triangle + k];
                System.arraycopy(p, 3 * v, points, 3 * (base + k), 3);
                System.arraycopy(n, 3 * v, normals, 3 * (base + k), 3);
                System.arraycopy(uv, 2 * v, uvs, 2 * (base + k), 2);
            }
            int f = 9 * faceCount;
            for (int k = 0; k < 3; k++) {
                faces[f + 3 * k] = base + k;
                faces[f + 3 * k + 1] = base + k;
                faces[f + 3 * k + 2] = base + k;
            }
            vertexCount += 3;
            faceCount++;
        }

        private void ensure(int more) {
            if (3 * (vertexCount + more) > points.length) {
                int size = points.length * 2;
                points = Arrays.copyOf(points, size);
                normals = Arrays.copyOf(normals, size);
                uvs = Arrays.copyOf(uvs, size / 3 * 2);
            }
            if (9 * (faceCount + 1) > faces.length) {
                faces = Arrays.copyOf(faces, faces.length * 2);
            }
        }

        TriangleMesh build() {
            TriangleMesh mesh = new TriangleMesh(VertexFormat.POINT_NORMAL_TEXCOORD);
            mesh.getPoints().setAll(points, 0, 3 * vertexCount);
            mesh.getNormals().setAll(normals, 0, 3 * vertexCount);
            mesh.getTexCoords().setAll(uvs, 0, 2 * vertexCount);
            mesh.getFaces().setAll(faces, 0, 9 * faceCount);
            return mesh;
        }
    }

    private static final class Builder {
        private final GltfModel model;
        private final VehicleParams params;
        private final String name;
        private final Map<Integer, PhongMaterial> materials = new HashMap<>();
        private final Map<Integer, PhongMaterial> brightMaterials = new HashMap<>();
        private final Map<Integer, Image> images = new HashMap<>();

        Builder(GltfModel model, VehicleParams params, String name) {
            this.model = model;
            this.params = params;
            this.name = name;
        }

        CarVisual build() {
            List<Part> parts = collectParts();

            // ---- car frame from the four wheels -------------------------------------------
            double[][] wheelCentre = new double[4][];
            for (int w = 0; w < 4; w++) {
                double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
                double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (Part p : parts) {
                    if (p.wheel() == w) {
                        bounds(p.positions(), min, max);
                    }
                }
                if (min[0] == Double.MAX_VALUE) {
                    throw new IllegalStateException("Model has no wheel named like 'tire FL/FR/RL/RR'");
                }
                wheelCentre[w] = new double[] {(min[0] + max[0]) / 2, (min[1] + max[1]) / 2, (min[2] + max[2]) / 2};
            }
            double[] front = mid(wheelCentre[0], wheelCentre[1]);
            double[] rear = mid(wheelCentre[2], wheelCentre[3]);
            double[] fwd = normalise(sub(front, rear));
            double[] leftRaw = sub(wheelCentre[0], wheelCentre[1]);
            double[] left = normalise(sub(leftRaw, scale(fwd, dot(leftRaw, fwd))));
            double[] up = cross(fwd, left);
            double modelWheelbase = dot(sub(front, rear), fwd);
            double s = params.wheelbase() / modelWheelbase;
            double groundUp = Double.MAX_VALUE;
            for (Part p : parts) {
                if (p.wheel() >= 0) {
                    float[] pos = p.positions();
                    for (int i = 0; i < pos.length; i += 3) {
                        groundUp = Math.min(groundUp, pos[i] * up[0] + pos[i + 1] * up[1] + pos[i + 2] * up[2]);
                    }
                }
            }
            double cgFwd = dot(front, fwd) - params.cgToFrontAxle() / s;
            double centreLeft = (dot(front, left) + dot(rear, left)) / 2;
            Frame frame = new Frame(fwd, up, left, s, cgFwd, groundUp, centreLeft);

            // ---- transform into the car frame --------------------------------------------
            List<Part> carParts = new ArrayList<>(parts.size());
            for (Part p : parts) {
                carParts.add(frame.apply(p));
            }
            double[][] hubs = new double[4][];
            for (int w = 0; w < 4; w++) {
                hubs[w] = frame.point(wheelCentre[w]);
            }

            double[] bodyMin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] bodyMax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            double[] headMin = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] headMax = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (Part p : carParts) {
                bounds(p.positions(), bodyMin, bodyMax);
                if (p.role() == Role.HEAD) {
                    bounds(p.positions(), headMin, headMax);
                }
            }

            // ---- meshes --------------------------------------------------------------------
            Map<String, Accumulator> groups = new LinkedHashMap<>();
            Map<String, int[]> groupInfo = new HashMap<>(); // role, on, wheel, material, side
            for (Part p : carParts) {
                float[] pos = p.positions();
                float[] local = pos;
                if (p.wheel() >= 0) {
                    local = pos.clone();
                    double[] hub = hubs[p.wheel()];
                    for (int i = 0; i < local.length; i += 3) {
                        local[i] -= (float) hub[0];
                        local[i + 1] -= (float) hub[1];
                        local[i + 2] -= (float) hub[2];
                    }
                }
                int[] idx = p.indices();
                for (int t = 0; t < idx.length / 3; t++) {
                    int side = 0;
                    if (p.role() == Role.INDICATOR) {
                        double z = (pos[3 * idx[3 * t] + 2] + pos[3 * idx[3 * t + 1] + 2] + pos[3 * idx[3 * t + 2] + 2]) / 3;
                        side = z > 0 ? 1 : -1;
                    }
                    String key = p.role() + "|" + p.on() + "|" + p.wheel() + "|" + p.material() + "|" + side;
                    Accumulator acc = groups.get(key);
                    if (acc == null) {
                        acc = new Accumulator();
                        groups.put(key, acc);
                        groupInfo.put(key, new int[] {p.role().ordinal(), p.on() ? 1 : 0, p.wheel(), p.material(), side});
                    }
                    acc.add(local, p.normals(), p.uvs(), idx, t);
                }
            }

            Group body = new Group();
            List<MeshView> transparent = new ArrayList<>();
            Group[] wheels = {new Group(), new Group(), new Group(), new Group()};
            LampViews lamps = new LampViews();
            for (Map.Entry<String, Accumulator> e : groups.entrySet()) {
                int[] info = groupInfo.get(e.getKey());
                Role role = Role.values()[info[0]];
                boolean on = info[1] == 1;
                int wheel = info[2];
                int material = info[3];
                MeshView view = new MeshView(e.getValue().build());
                view.setMaterial(material(material));
                GltfModel.Material m = material >= 0 ? model.materials().get(material) : null;
                boolean blend = m != null && "BLEND".equals(m.alphaMode());
                view.setCullFace(m != null && m.doubleSided() || blend ? CullFace.NONE : CullFace.BACK);
                if (wheel >= 0) {
                    wheels[wheel].getChildren().add(view);
                    continue;
                }
                if (blend) {
                    transparent.add(view);
                } else {
                    body.getChildren().add(view);
                }
                lamps.register(role, on, info[4], view, material, this);
            }
            body.getChildren().addAll(transparent); // drawn last so glass does not hide what is behind it

            double length = bodyMax[0] - bodyMin[0];
            double width = bodyMax[2] - bodyMin[2];
            Box shadow = new Box(length * 1.02, 0.002, width * 0.98);
            shadow.setMaterial(Materials.matte("#08090a"));
            shadow.getTransforms().add(new Translate((bodyMax[0] + bodyMin[0]) / 2, -0.004, (bodyMax[2] + bodyMin[2]) / 2));
            body.getChildren().add(0, shadow);

            lamps.addReverseLamps(body, bodyMin[0], width);
            lamps.showAll(LightState.off());

            int triangles = carParts.stream().mapToInt(p -> p.indices().length / 3).sum();
            System.getLogger(ImportedCar.class.getName()).log(System.Logger.Level.INFO,
                    "Car model {0}: {1} triangles in {2} meshes, {3} x {4} m", name, triangles, groups.size(),
                    String.format("%.2f", length), String.format("%.2f", width));

            boolean headFound = headMin[0] != Double.MAX_VALUE;
            double lampHeight = headFound ? -(headMin[1] + headMax[1]) / 2 : 0.7;
            double lampSpacing = headFound ? (headMax[2] - headMin[2]) * 0.8 : width * 0.7;
            return new CarVisual(name, body, wheels, hubs, lamps::showAll, length, bodyMax[0], lampHeight,
                    lampSpacing);
        }

        // ---- parts ---------------------------------------------------------------------------

        private List<Part> collectParts() {
            List<GltfModel.Node> nodes = model.nodes();
            Set<String> meshNames = new HashSet<>();
            for (GltfModel.Node n : nodes) {
                if (n.mesh() >= 0) {
                    meshNames.add(n.name());
                }
            }
            List<Part> parts = new ArrayList<>();
            for (GltfModel.Node n : nodes) {
                if (n.mesh() < 0) {
                    continue;
                }
                Role role = Role.STATIC;
                boolean on = false;
                String nodeName = n.name();
                if (nodeName.endsWith(LIGHTS_ON)) {
                    role = lampRole(nodeName);
                    on = true;
                } else if (nodeName.endsWith(TURN_SIGNAL)) {
                    role = Role.INDICATOR;
                    on = true;
                } else if (meshNames.contains(nodeName + LIGHTS_ON)) {
                    role = lampRole(nodeName);
                } else if (meshNames.contains(nodeName + TURN_SIGNAL)) {
                    role = Role.INDICATOR;
                }
                if (role == Role.STATIC && isHidden(n)) {
                    continue; // an unused variant (e.g. a folded mirror)
                }
                int wheel = wheelIndex(n);
                double[] m = n.world();
                double det = det3(m);
                double[] normalMatrix = cofactor3(m, Math.signum(det));
                for (GltfModel.Primitive prim : model.primitives(n.mesh())) {
                    if (isInvisible(prim.material())) {
                        continue;
                    }
                    float[] pos = transformPoints(m, prim.positions());
                    float[] nrm = prim.normals() == null ? faceNormals(pos, prim.indices())
                            : transformNormals(normalMatrix, prim.normals());
                    float[] uv = prim.uvs().clone();
                    GltfModel.TextureRef ref = textureFor(prim.material());
                    if (ref != null && !ref.isIdentity()) {
                        for (int i = 0; i < uv.length; i += 2) {
                            ref.apply(uv, i);
                        }
                    }
                    int[] idx = prim.indices();
                    if (det < 0) {
                        idx = idx.clone();
                        for (int i = 0; i < idx.length; i += 3) {
                            int t = idx[i + 1];
                            idx[i + 1] = idx[i + 2];
                            idx[i + 2] = t;
                        }
                    }
                    parts.add(new Part(role, on, wheel, prim.material(), pos, nrm, uv, idx));
                }
            }
            return parts;
        }

        private static Role lampRole(String nodeName) {
            String n = nodeName.toUpperCase(Locale.ROOT);
            if (n.contains("HMSL") || n.contains("STOP")) {
                return Role.BRAKE;
            }
            if (n.contains("LOW") || n.contains("HIGH") || n.contains("HEAD")) {
                return Role.HEAD;
            }
            return Role.TAIL;
        }

        private boolean isHidden(GltfModel.Node n) {
            for (GltfModel.Node at = n; ; at = model.nodes().get(at.parent())) {
                if (at.hidden()) {
                    return true;
                }
                if (at.parent() < 0) {
                    return false;
                }
            }
        }

        private int wheelIndex(GltfModel.Node n) {
            for (GltfModel.Node at = n; ; at = model.nodes().get(at.parent())) {
                Matcher matcher = WHEEL.matcher(at.name());
                if (matcher.find()) {
                    return switch (matcher.group(1).toUpperCase(Locale.ROOT)) {
                        case "FL" -> 0;
                        case "FR" -> 1;
                        case "RL" -> 2;
                        default -> 3;
                    };
                }
                if (at.parent() < 0) {
                    return -1;
                }
            }
        }

        private boolean isInvisible(int material) {
            if (material < 0) {
                return false;
            }
            GltfModel.Material m = model.materials().get(material);
            return ("BLEND".equals(m.alphaMode()) && m.baseColor()[3] < 0.02)
                    || HIDDEN_MATERIAL.matcher(m.name()).matches();
        }

        private GltfModel.TextureRef textureFor(int material) {
            if (material < 0) {
                return null;
            }
            GltfModel.Material m = model.materials().get(material);
            return m.baseTexture().present() ? m.baseTexture() : m.emissiveTexture().present() ? m.emissiveTexture() : null;
        }

        // ---- materials -----------------------------------------------------------------------

        PhongMaterial material(int index) {
            return materials.computeIfAbsent(index, i -> createMaterial(i, 1.0));
        }

        /** The same material with its glow boosted (a tail lamp becoming a brake lamp). */
        PhongMaterial brightMaterial(int index) {
            return brightMaterials.computeIfAbsent(index, i -> createMaterial(i, 2.2));
        }

        private PhongMaterial createMaterial(int index, double glowBoost) {
            if (index < 0) {
                return Materials.matte("#9aa0a6");
            }
            GltfModel.Material m = model.materials().get(index);
            double[] bc = m.baseColor();
            double alpha = "BLEND".equals(m.alphaMode()) ? bc[3] : 1;
            PhongMaterial pm = new PhongMaterial(Color.color(srgb(bc[0]), srgb(bc[1]), srgb(bc[2]), alpha));
            if (m.baseTexture().present()) {
                Image image = image(m.baseTexture().texture());
                if (image != null) {
                    pm.setDiffuseMap(image);
                }
            }
            double r = Math.max(0.05, Math.min(1, m.roughness()));
            double gloss = (1 - r) * (1 - r);
            pm.setSpecularColor(Color.gray(Math.min(1, 0.06 + 0.9 * gloss)));
            pm.setSpecularPower(Math.max(6, Math.min(160, 2 / (r * r))));

            double[] e = m.emissive();
            boolean emits = e[0] + e[1] + e[2] > 0.001;
            if (emits) {
                Image glow = m.emissiveTexture().present() ? image(m.emissiveTexture().texture()) : null;
                if (glow != null) {
                    pm.setSelfIlluminationMap(glowBoost == 1 && e[0] >= 0.99 && e[1] >= 0.99 && e[2] >= 0.99
                            ? glow : tinted(glow, e, glowBoost));
                } else {
                    pm.setSelfIlluminationMap(solid(Color.color(clamp(srgb(e[0]) * glowBoost),
                            clamp(srgb(e[1]) * glowBoost), clamp(srgb(e[2]) * glowBoost))));
                }
            }
            return pm;
        }

        private Image image(int texture) {
            int source = model.textureSource(texture);
            if (source < 0) {
                return null;
            }
            return images.computeIfAbsent(source, s -> {
                byte[] bytes = model.textureImage(texture);
                if (bytes == null) {
                    return null;
                }
                Image image = new Image(new ByteArrayInputStream(bytes));
                return image.isError() ? null : image;
            });
        }

        private static Image tinted(Image source, double[] factor, double boost) {
            int w = (int) source.getWidth();
            int h = (int) source.getHeight();
            WritableImage out = new WritableImage(w, h);
            PixelReader in = source.getPixelReader();
            PixelWriter px = out.getPixelWriter();
            double fr = Math.min(1, factor[0]) * boost;
            double fg = Math.min(1, factor[1]) * boost;
            double fb = Math.min(1, factor[2]) * boost;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    Color c = in.getColor(x, y);
                    px.setColor(x, y, Color.color(clamp(c.getRed() * fr), clamp(c.getGreen() * fg),
                            clamp(c.getBlue() * fb)));
                }
            }
            return out;
        }

        private static WritableImage solid(Color colour) {
            WritableImage image = new WritableImage(1, 1);
            image.getPixelWriter().setColor(0, 0, colour);
            return image;
        }
    }

    /** Switchable lamp meshes, grouped by what they do. */
    private static final class LampViews {
        private final List<MeshView> headOn = new ArrayList<>();
        private final List<MeshView> headOff = new ArrayList<>();
        private final List<MeshView> tailOn = new ArrayList<>();
        private final List<MeshView> tailOff = new ArrayList<>();
        private final List<PhongMaterial[]> tailMaterials = new ArrayList<>(); // normal, bright
        private final List<MeshView> brakeOn = new ArrayList<>();
        private final List<MeshView> brakeOff = new ArrayList<>();
        private final List<MeshView> leftOn = new ArrayList<>();
        private final List<MeshView> leftOff = new ArrayList<>();
        private final List<MeshView> rightOn = new ArrayList<>();
        private final List<MeshView> rightOff = new ArrayList<>();
        private final List<Node> reverse = new ArrayList<>();

        void register(Role role, boolean on, int side, MeshView view, int material, Builder builder) {
            switch (role) {
                case HEAD -> (on ? headOn : headOff).add(view);
                case BRAKE -> (on ? brakeOn : brakeOff).add(view);
                case TAIL -> {
                    if (on) {
                        tailOn.add(view);
                        tailMaterials.add(new PhongMaterial[] {builder.material(material), builder.brightMaterial(material)});
                    } else {
                        tailOff.add(view);
                    }
                }
                case INDICATOR -> {
                    if (side > 0) {
                        (on ? leftOn : leftOff).add(view);
                    } else {
                        (on ? rightOn : rightOff).add(view);
                    }
                }
                default -> { }
            }
        }

        /** The model has no separate reversing lamps, so two small white lamps sit in the rear bumper. */
        void addReverseLamps(Group body, double rearX, double width) {
            PhongMaterial white = Materials.glowing(Color.web("#f4f6f8"));
            for (double side : new double[] {1, -1}) {
                Box lamp = new Box(0.03, 0.05, 0.12);
                lamp.setMaterial(white);
                lamp.getTransforms().add(new Translate(rearX + 0.03, -0.52, side * width * 0.36));
                lamp.setVisible(false);
                reverse.add(lamp);
                body.getChildren().add(lamp);
            }
        }

        void showAll(LightState s) {
            swap(headOn, headOff, s.lowBeam() || s.highBeam());
            boolean tail = s.tailLights() || s.brakeLights();
            swap(tailOn, tailOff, tail);
            for (int i = 0; i < tailOn.size(); i++) {
                tailOn.get(i).setMaterial(tailMaterials.get(i)[s.brakeLights() ? 1 : 0]);
            }
            swap(brakeOn, brakeOff, s.brakeLights());
            swap(leftOn, leftOff, s.leftLit());
            swap(rightOn, rightOff, s.rightLit());
            for (Node n : reverse) {
                n.setVisible(s.reverseLights());
            }
        }

        private static void swap(List<MeshView> on, List<MeshView> off, boolean lit) {
            for (MeshView v : on) {
                v.setVisible(lit);
            }
            for (MeshView v : off) {
                v.setVisible(!lit || on.isEmpty());
            }
        }
    }

    /** Model space to car frame: X forward, Y down, Z left, metres, origin under the CG. */
    private record Frame(double[] fwd, double[] up, double[] left, double scale, double cgFwd, double groundUp,
                         double centreLeft) {

        double[] point(double[] p) {
            return new double[] {
                    scale * (dot(p, fwd) - cgFwd),
                    -scale * (dot(p, up) - groundUp),
                    scale * (dot(p, left) - centreLeft)};
        }

        Part apply(Part p) {
            float[] in = p.positions();
            float[] out = new float[in.length];
            float[] nin = p.normals();
            float[] nout = new float[nin.length];
            double[] v = new double[3];
            for (int i = 0; i < in.length; i += 3) {
                v[0] = in[i];
                v[1] = in[i + 1];
                v[2] = in[i + 2];
                double[] q = point(v);
                out[i] = (float) q[0];
                out[i + 1] = (float) q[1];
                out[i + 2] = (float) q[2];
                v[0] = nin[i];
                v[1] = nin[i + 1];
                v[2] = nin[i + 2];
                nout[i] = (float) dot(v, fwd);
                nout[i + 1] = (float) -dot(v, up);
                nout[i + 2] = (float) dot(v, left);
            }
            return new Part(p.role(), p.on(), p.wheel(), p.material(), out, nout, p.uvs(), p.indices());
        }
    }

    // ---- small maths helpers --------------------------------------------------------------

    private static float[] transformPoints(double[] m, float[] p) {
        float[] out = new float[p.length];
        for (int i = 0; i < p.length; i += 3) {
            double x = p[i];
            double y = p[i + 1];
            double z = p[i + 2];
            out[i] = (float) (m[0] * x + m[4] * y + m[8] * z + m[12]);
            out[i + 1] = (float) (m[1] * x + m[5] * y + m[9] * z + m[13]);
            out[i + 2] = (float) (m[2] * x + m[6] * y + m[10] * z + m[14]);
        }
        return out;
    }

    private static float[] transformNormals(double[] n, float[] normals) {
        float[] out = new float[normals.length];
        for (int i = 0; i < normals.length; i += 3) {
            double x = normals[i];
            double y = normals[i + 1];
            double z = normals[i + 2];
            double nx = n[0] * x + n[3] * y + n[6] * z;
            double ny = n[1] * x + n[4] * y + n[7] * z;
            double nz = n[2] * x + n[5] * y + n[8] * z;
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-12) {
                len = 1;
            }
            out[i] = (float) (nx / len);
            out[i + 1] = (float) (ny / len);
            out[i + 2] = (float) (nz / len);
        }
        return out;
    }

    /** Flat normals for meshes that come without any. */
    private static float[] faceNormals(float[] p, int[] idx) {
        float[] n = new float[p.length];
        for (int t = 0; t < idx.length; t += 3) {
            int a = 3 * idx[t];
            int b = 3 * idx[t + 1];
            int c = 3 * idx[t + 2];
            double ux = p[b] - p[a];
            double uy = p[b + 1] - p[a + 1];
            double uz = p[b + 2] - p[a + 2];
            double vx = p[c] - p[a];
            double vy = p[c + 1] - p[a + 1];
            double vz = p[c + 2] - p[a + 2];
            double nx = uy * vz - uz * vy;
            double ny = uz * vx - ux * vz;
            double nz = ux * vy - uy * vx;
            for (int k : new int[] {a, b, c}) {
                n[k] += (float) nx;
                n[k + 1] += (float) ny;
                n[k + 2] += (float) nz;
            }
        }
        for (int i = 0; i < n.length; i += 3) {
            double len = Math.sqrt(n[i] * n[i] + n[i + 1] * n[i + 1] + n[i + 2] * n[i + 2]);
            if (len > 1e-12) {
                n[i] /= (float) len;
                n[i + 1] /= (float) len;
                n[i + 2] /= (float) len;
            }
        }
        return n;
    }

    /** Determinant of the upper 3x3 of a column-major 4x4 matrix. */
    private static double det3(double[] m) {
        return m[0] * (m[5] * m[10] - m[9] * m[6])
                - m[4] * (m[1] * m[10] - m[9] * m[2])
                + m[8] * (m[1] * m[6] - m[5] * m[2]);
    }

    /**
     * Cofactor matrix of the upper 3x3 (column-major 3x3 result), which transforms normals like
     * the inverse transpose up to scale.
     */
    private static double[] cofactor3(double[] m, double sign) {
        double a = m[0], b = m[4], c = m[8];
        double d = m[1], e = m[5], f = m[9];
        double g = m[2], h = m[6], i = m[10];
        double[] r = {
                e * i - f * h, -(b * i - c * h), b * f - c * e,
                -(d * i - f * g), a * i - c * g, -(a * f - c * d),
                d * h - e * g, -(a * h - b * g), a * e - b * d};
        // r is the cofactor matrix C in column-major order; normals transform as n' = C n / det.
        double k = sign == 0 ? 1 : sign;
        for (int j = 0; j < 9; j++) {
            r[j] *= k;
        }
        return r;
    }

    private static void bounds(float[] p, double[] min, double[] max) {
        for (int i = 0; i < p.length; i += 3) {
            for (int k = 0; k < 3; k++) {
                min[k] = Math.min(min[k], p[i + k]);
                max[k] = Math.max(max[k], p[i + k]);
            }
        }
    }

    private static double[] mid(double[] a, double[] b) {
        return new double[] {(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2};
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[] {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] a, double s) {
        return new double[] {a[0] * s, a[1] * s, a[2] * s};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] normalise(double[] a) {
        double len = Math.sqrt(dot(a, a));
        return new double[] {a[0] / len, a[1] / len, a[2] / len};
    }

    private static double srgb(double linear) {
        double c = Math.max(0, Math.min(1, linear));
        return c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
