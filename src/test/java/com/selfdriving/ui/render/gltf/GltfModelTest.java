package com.selfdriving.ui.render.gltf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.selfdriving.util.Json;

class GltfModelTest {

    /** A .glb with one triangle used by two nodes: a parent that moves it, and a zero-scaled child. */
    private static byte[] sampleGlb() {
        ByteBuffer bin = ByteBuffer.allocate(36 + 8).order(ByteOrder.LITTLE_ENDIAN);
        float[] positions = {0, 0, 0, 1, 0, 0, 0, 1, 0};
        for (float f : positions) {
            bin.putFloat(f);
        }
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);
        String json = """
                {"asset":{"version":"2.0"},
                 "buffers":[{"byteLength":44}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0}]}],
                 "materials":[{"name":"paint","pbrMetallicRoughness":{"baseColorFactor":[0.5,0.25,1,1],"roughnessFactor":0.4,
                    "baseColorTexture":{"index":0,"extensions":{"KHR_texture_transform":{"offset":[0.5,0],"scale":[2,2]}}}}}],
                 "nodes":[{"name":"parent","translation":[10,0,0],"children":[1],"mesh":0},
                          {"name":"lamp Lights_On","scale":[0,0,0],"rotation":[0,0,0.7071068,0.7071068],"mesh":0}],
                 "scenes":[{"nodes":[0]}]}""";
        byte[] jsonBytes = pad(json.getBytes(StandardCharsets.UTF_8), (byte) ' ');
        byte[] binBytes = pad(bin.array(), (byte) 0);
        ByteBuffer glb = ByteBuffer.allocate(12 + 8 + jsonBytes.length + 8 + binBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(glb.capacity());
        glb.putInt(jsonBytes.length).putInt(0x4E4F534A).put(jsonBytes);
        glb.putInt(binBytes.length).putInt(0x004E4942).put(binBytes);
        return glb.array();
    }

    private static byte[] pad(byte[] data, byte filler) {
        int length = (data.length + 3) / 4 * 4;
        byte[] out = java.util.Arrays.copyOf(data, length);
        for (int i = data.length; i < length; i++) {
            out[i] = filler;
        }
        return out;
    }

    @Test
    void readsMeshesNodesAndMaterials() {
        GltfModel model = GltfModel.readGlb(sampleGlb());
        List<GltfModel.Primitive> prims = model.primitives(0);
        assertEquals(1, prims.size());
        assertArrayEquals(new int[] {0, 1, 2}, prims.get(0).indices());
        assertEquals(1f, prims.get(0).positions()[3]);

        GltfModel.Node parent = model.nodes().get(0);
        GltfModel.Node child = model.nodes().get(1);
        assertFalse(parent.hidden());
        assertTrue(child.hidden(), "zero scale marks a switched-off part");
        assertEquals(10, parent.world()[12], 1e-9, "translation");
        // The child keeps its real size and rotation (90 degrees about Z) under the parent.
        assertEquals(0, child.world()[0], 1e-6);
        assertEquals(1, child.world()[1], 1e-6);
        assertEquals(10, child.world()[12], 1e-9);

        GltfModel.Material paint = model.materials().get(0);
        assertEquals("paint", paint.name());
        assertEquals(0.4, paint.roughness(), 1e-9);
        float[] uv = {0.25f, 0.5f};
        paint.baseTexture().apply(uv, 0);
        assertEquals(1.0f, uv[0], 1e-6, "scale then offset");
        assertEquals(1.0f, uv[1], 1e-6);
    }

    @Test
    void rejectsOtherFiles() {
        assertThrows(IllegalArgumentException.class, () -> GltfModel.readGlb(new byte[40]));
    }

    @Test
    void jsonParser() {
        Map<String, Object> o = Json.object(Json.parse("{\"a\": [1, 2.5e1, -3], \"b\": \"x\\u0041\\n\", \"c\": true, \"d\": null}"));
        assertEquals(List.of(1.0, 25.0, -3.0), o.get("a"));
        assertEquals("xA\n", o.get("b"));
        assertEquals(Boolean.TRUE, o.get("c"));
        assertTrue(o.containsKey("d"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\": }"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1, 2] x"));
    }
}
