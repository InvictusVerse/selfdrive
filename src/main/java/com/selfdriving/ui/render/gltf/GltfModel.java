package com.selfdriving.ui.render.gltf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.selfdriving.util.Json;

/**
 * Reads a binary glTF 2.0 file (.glb): the node tree with world transforms, triangle meshes,
 * materials and embedded images. Plain data only, no JavaFX, so it can run on any thread.
 *
 * <p>Supports what typical exported car models use: float positions/normals/UVs, 8/16/32-bit
 * indices, TRS or matrix nodes, the metallic-roughness material with base colour and emissive
 * textures, and {@code KHR_texture_transform}. Sparse accessors, skins and morph targets are
 * not needed and not supported.
 */
public final class GltfModel {

    private static final int MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    /**
     * One node of the scene tree.
     *
     * @param world  column-major 4x4 transform from this node to the scene root
     * @param hidden the node is switched off by a zero scale (see {@link #isHiddenByScale})
     */
    public record Node(int index, String name, int mesh, int parent, int[] children, double[] world,
                       boolean hidden) {
    }

    /** One drawable part of a mesh, already expanded to plain arrays. */
    public record Primitive(float[] positions, float[] normals, float[] uvs, int[] indices, int material) {

        public int triangles() {
            return indices.length / 3;
        }
    }

    /**
     * A texture reference with its UV transform.
     *
     * @param texture   texture index, or -1 for none
     * @param transform {offsetU, offsetV, rotation, scaleU, scaleV}
     */
    public record TextureRef(int texture, double[] transform) {

        static final TextureRef NONE = new TextureRef(-1, new double[] {0, 0, 0, 1, 1});

        public boolean present() {
            return texture >= 0;
        }

        /** Applies the transform to a UV pair in place. */
        public void apply(float[] uv, int offset) {
            double u = uv[offset];
            double v = uv[offset + 1];
            double su = u * transform[3];
            double sv = v * transform[4];
            double c = Math.cos(transform[2]);
            double s = Math.sin(transform[2]);
            uv[offset] = (float) (c * su + s * sv + transform[0]);
            uv[offset + 1] = (float) (-s * su + c * sv + transform[1]);
        }

        public boolean isIdentity() {
            return transform[0] == 0 && transform[1] == 0 && transform[2] == 0 && transform[3] == 1
                    && transform[4] == 1;
        }
    }

    /** Material in glTF's metallic-roughness model (linear colours). */
    public record Material(String name, double[] baseColor, TextureRef baseTexture, double[] emissive,
                           TextureRef emissiveTexture, double metallic, double roughness, String alphaMode,
                           boolean doubleSided) {
    }

    private final Map<String, Object> json;
    private final ByteBuffer bin;
    private final List<Node> nodes = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();

    private GltfModel(Map<String, Object> json, ByteBuffer bin) {
        this.json = json;
        this.bin = bin;
        readNodes();
        readMaterials();
    }

    /** Parses a .glb file held in memory. */
    public static GltfModel readGlb(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length < 20 || b.getInt(0) != MAGIC) {
            throw new IllegalArgumentException("Not a binary glTF file");
        }
        if (b.getInt(4) != 2) {
            throw new IllegalArgumentException("Only glTF 2.0 is supported");
        }
        int length = Math.min(b.getInt(8), data.length);
        int offset = 12;
        String jsonText = null;
        ByteBuffer binary = null;
        while (offset + 8 <= length) {
            int chunkLength = b.getInt(offset);
            int chunkType = b.getInt(offset + 4);
            int start = offset + 8;
            if (chunkType == CHUNK_JSON) {
                jsonText = new String(data, start, chunkLength, StandardCharsets.UTF_8);
            } else if (chunkType == CHUNK_BIN) {
                binary = ByteBuffer.wrap(data, start, chunkLength).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            offset = start + chunkLength;
        }
        if (jsonText == null) {
            throw new IllegalArgumentException("glTF file has no JSON chunk");
        }
        return new GltfModel(Json.object(Json.parse(jsonText)),
                binary == null ? ByteBuffer.allocate(0) : binary);
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Material> materials() {
        return materials;
    }

    public int meshCount() {
        return Json.array(json, "meshes").size();
    }

    /** The primitives of a mesh, with triangle-list indices. */
    public List<Primitive> primitives(int mesh) {
        Map<String, Object> m = Json.object(Json.array(json, "meshes").get(mesh));
        List<Primitive> result = new ArrayList<>();
        for (Object o : Json.array(m, "primitives")) {
            Map<String, Object> p = Json.object(o);
            int mode = Json.integer(p, "mode", 4);
            if (mode != 4) {
                continue; // only triangle lists
            }
            Map<String, Object> attributes = Json.object(p, "attributes");
            if (!attributes.containsKey("POSITION")) {
                continue;
            }
            float[] positions = floats(Json.integer(attributes, "POSITION", -1), 3);
            int vertexCount = positions.length / 3;
            float[] normals = attributes.containsKey("NORMAL")
                    ? floats(Json.integer(attributes, "NORMAL", -1), 3) : null;
            float[] uvs = attributes.containsKey("TEXCOORD_0")
                    ? floats(Json.integer(attributes, "TEXCOORD_0", -1), 2) : new float[2 * vertexCount];
            int[] indices;
            if (p.containsKey("indices")) {
                indices = ints(Json.integer(p, "indices", -1));
            } else {
                indices = new int[vertexCount];
                for (int i = 0; i < vertexCount; i++) {
                    indices[i] = i;
                }
            }
            result.add(new Primitive(positions, normals, uvs, indices, Json.integer(p, "material", -1)));
        }
        return result;
    }

    /** The encoded image (JPEG or PNG) behind a texture, or null. */
    public byte[] textureImage(int texture) {
        List<Object> textures = Json.array(json, "textures");
        if (texture < 0 || texture >= textures.size()) {
            return null;
        }
        int source = Json.integer(Json.object(textures.get(texture)), "source", -1);
        List<Object> images = Json.array(json, "images");
        if (source < 0 || source >= images.size()) {
            return null;
        }
        Map<String, Object> image = Json.object(images.get(source));
        int view = Json.integer(image, "bufferView", -1);
        if (view < 0) {
            return null; // external image files are not supported
        }
        Map<String, Object> bv = Json.object(Json.array(json, "bufferViews").get(view));
        int offset = Json.integer(bv, "byteOffset", 0);
        int length = Json.integer(bv, "byteLength", 0);
        byte[] bytes = new byte[length];
        bin.get(offset, bytes);
        return bytes;
    }

    /** The image index a texture points to (to share decoded images), or -1. */
    public int textureSource(int texture) {
        List<Object> textures = Json.array(json, "textures");
        if (texture < 0 || texture >= textures.size()) {
            return -1;
        }
        return Json.integer(Json.object(textures.get(texture)), "source", -1);
    }

    // ---- nodes ----------------------------------------------------------------------------

    private void readNodes() {
        List<Object> list = Json.array(json, "nodes");
        int n = list.size();
        int[] parent = new int[n];
        Arrays.fill(parent, -1);
        double[][] local = new double[n][];
        int[][] children = new int[n][];
        for (int i = 0; i < n; i++) {
            Map<String, Object> node = Json.object(list.get(i));
            local[i] = localMatrix(node);
            List<Object> c = Json.array(node, "children");
            children[i] = new int[c.size()];
            for (int k = 0; k < c.size(); k++) {
                children[i][k] = (int) Math.round((Double) c.get(k));
                parent[children[i][k]] = i;
            }
        }
        double[][] world = new double[n][];
        for (int i = 0; i < n; i++) {
            world[i] = worldMatrix(i, parent, local, world);
        }
        for (int i = 0; i < n; i++) {
            Map<String, Object> node = Json.object(list.get(i));
            nodes.add(new Node(i, Json.string(node, "name", "node" + i), Json.integer(node, "mesh", -1),
                    parent[i], children[i], world[i], isHiddenByScale(node)));
        }
    }

    /**
     * Models often switch parts (e.g. lit and unlit lamps) by scaling the inactive one to zero.
     * Such nodes are reported as hidden with their full-size transform.
     */
    private static boolean isHiddenByScale(Map<String, Object> node) {
        double[] s = Json.numbers(node, "scale", null);
        return s != null && s.length == 3 && s[0] == 0 && s[1] == 0 && s[2] == 0;
    }

    private static double[] worldMatrix(int i, int[] parent, double[][] local, double[][] world) {
        if (world[i] != null) {
            return world[i];
        }
        world[i] = parent[i] < 0 ? local[i] : multiply(worldMatrix(parent[i], parent, local, world), local[i]);
        return world[i];
    }

    private static double[] localMatrix(Map<String, Object> node) {
        double[] matrix = Json.numbers(node, "matrix", null);
        if (matrix != null) {
            return matrix;
        }
        double[] t = Json.numbers(node, "translation", new double[] {0, 0, 0});
        double[] r = Json.numbers(node, "rotation", new double[] {0, 0, 0, 1});
        double[] s = Json.numbers(node, "scale", new double[] {1, 1, 1});
        if (isHiddenByScale(node)) {
            s = new double[] {1, 1, 1}; // switched-off variant: keep its real shape, hide it separately
        }
        double x = r[0];
        double y = r[1];
        double z = r[2];
        double w = r[3];
        // Column-major rotation * scale, then translation.
        return new double[] {
                (1 - 2 * (y * y + z * z)) * s[0], (2 * (x * y + z * w)) * s[0], (2 * (x * z - y * w)) * s[0], 0,
                (2 * (x * y - z * w)) * s[1], (1 - 2 * (x * x + z * z)) * s[1], (2 * (y * z + x * w)) * s[1], 0,
                (2 * (x * z + y * w)) * s[2], (2 * (y * z - x * w)) * s[2], (1 - 2 * (x * x + y * y)) * s[2], 0,
                t[0], t[1], t[2], 1};
    }

    /** Column-major 4x4 product a * b. */
    public static double[] multiply(double[] a, double[] b) {
        double[] r = new double[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                double sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                r[col * 4 + row] = sum;
            }
        }
        return r;
    }

    // ---- materials ------------------------------------------------------------------------

    private void readMaterials() {
        for (Object o : Json.array(json, "materials")) {
            Map<String, Object> m = Json.object(o);
            Map<String, Object> pbr = Json.object(m, "pbrMetallicRoughness");
            materials.add(new Material(
                    Json.string(m, "name", ""),
                    Json.numbers(pbr, "baseColorFactor", new double[] {1, 1, 1, 1}),
                    textureRef(Json.object(pbr, "baseColorTexture")),
                    Json.numbers(m, "emissiveFactor", new double[] {0, 0, 0}),
                    textureRef(Json.object(m, "emissiveTexture")),
                    Json.number(pbr, "metallicFactor", 1),
                    Json.number(pbr, "roughnessFactor", 1),
                    Json.string(m, "alphaMode", "OPAQUE"),
                    Boolean.TRUE.equals(m.get("doubleSided"))));
        }
    }

    private static TextureRef textureRef(Map<String, Object> info) {
        if (info.isEmpty()) {
            return TextureRef.NONE;
        }
        int index = Json.integer(info, "index", -1);
        Map<String, Object> tt = Json.object(Json.object(info, "extensions"), "KHR_texture_transform");
        double[] offset = Json.numbers(tt, "offset", new double[] {0, 0});
        double[] scale = Json.numbers(tt, "scale", new double[] {1, 1});
        double rotation = Json.number(tt, "rotation", 0);
        return new TextureRef(index, new double[] {offset[0], offset[1], rotation, scale[0], scale[1]});
    }

    // ---- accessors ------------------------------------------------------------------------

    private float[] floats(int accessor, int components) {
        Map<String, Object> a = Json.object(Json.array(json, "accessors").get(accessor));
        int count = Json.integer(a, "count", 0);
        int type = Json.integer(a, "componentType", 5126);
        boolean normalized = Boolean.TRUE.equals(a.get("normalized"));
        float[] out = new float[count * components];
        if (!a.containsKey("bufferView")) {
            return out;
        }
        Map<String, Object> bv = Json.object(Json.array(json, "bufferViews").get(Json.integer(a, "bufferView", 0)));
        int base = Json.integer(bv, "byteOffset", 0) + Json.integer(a, "byteOffset", 0);
        int size = componentSize(type);
        int stride = Json.integer(bv, "byteStride", size * components);
        for (int i = 0; i < count; i++) {
            int at = base + i * stride;
            for (int c = 0; c < components; c++) {
                int p = at + c * size;
                float v = switch (type) {
                    case 5126 -> bin.getFloat(p);
                    case 5121 -> normalized ? (bin.get(p) & 0xFF) / 255f : bin.get(p) & 0xFF;
                    case 5123 -> normalized ? (bin.getShort(p) & 0xFFFF) / 65535f : bin.getShort(p) & 0xFFFF;
                    case 5120 -> normalized ? Math.max(bin.get(p) / 127f, -1f) : bin.get(p);
                    case 5122 -> normalized ? Math.max(bin.getShort(p) / 32767f, -1f) : bin.getShort(p);
                    default -> throw new IllegalArgumentException("Unsupported component type " + type);
                };
                out[i * components + c] = v;
            }
        }
        return out;
    }

    private int[] ints(int accessor) {
        Map<String, Object> a = Json.object(Json.array(json, "accessors").get(accessor));
        int count = Json.integer(a, "count", 0);
        int type = Json.integer(a, "componentType", 5125);
        Map<String, Object> bv = Json.object(Json.array(json, "bufferViews").get(Json.integer(a, "bufferView", 0)));
        int base = Json.integer(bv, "byteOffset", 0) + Json.integer(a, "byteOffset", 0);
        int size = componentSize(type);
        int stride = Json.integer(bv, "byteStride", size);
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int p = base + i * stride;
            out[i] = switch (type) {
                case 5121 -> bin.get(p) & 0xFF;
                case 5123 -> bin.getShort(p) & 0xFFFF;
                case 5125 -> bin.getInt(p);
                default -> throw new IllegalArgumentException("Unsupported index type " + type);
            };
        }
        return out;
    }

    private static int componentSize(int type) {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IllegalArgumentException("Unsupported component type " + type);
        };
    }
}
