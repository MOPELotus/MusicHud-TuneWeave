package indi.mopelotus.musichud.client.ui.hud.pipelines;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HudPortableUniformsTest {
    @Test void alignedProgressColorsSkipVec3Padding() {
        var values = transport("MHProgressStyle", 64);
        assertArrayEquals(new float[]{1, 2, 3}, values.get("u_Gradient"));
        assertArrayEquals(new float[]{5, 6, 7, 8}, values.get("u_PlayedColor"));
        assertArrayEquals(new float[]{9, 10, 11, 12}, values.get("u_CurrentColor"));
        assertArrayEquals(new float[]{13, 14, 15, 16}, values.get("u_BackgroundColor"));
    }

    @Test void matrixStylePaletteAndSessionFieldsKeepTheirOriginalOffsets() {
        var position = transport("MHAlbumPosition", 80);
        assertEquals(16, position.get("u_Translation").length);
        assertEquals(16, position.get("u_Translation")[15]);
        assertArrayEquals(new float[]{17, 18, 19, 20}, position.get("u_Style"));
        var colors = transport("MHNowPlayingThemeColor", 64);
        assertEquals(1, colors.get("u_Primary")[0]);
        assertEquals(5, colors.get("u_Secondary")[0]);
        assertEquals(9, colors.get("u_Bright")[0]);
        assertEquals(13, colors.get("u_Dark")[0]);
        assertArrayEquals(new float[]{1, 2, 3, 4}, transport("MHDynamicStatus", 16).get("u_Dynamic1"));
    }

    @Test void unknownAndTruncatedBlocksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> transport("foreign", 16));
        assertThrows(IndexOutOfBoundsException.class, () -> transport("MHProgressStyle", 16));
    }

    @Test void portableShadersPreserveTheFullEffectsAndDeclareEveryPlainUniform() throws Exception {
        for (String name : new String[]{"background", "album_image", "progress_bar"}) {
            var json = JsonParser.parseString(resource(name + "_portable.json")).getAsJsonObject();
            var declared = new java.util.HashSet<String>();
            json.getAsJsonArray("uniforms").forEach(v -> declared.add(v.getAsJsonObject().get("name").getAsString()));
            for (String extension : new String[]{"vsh", "fsh"}) {
                String original = resource(name + "." + extension);
                original = original.replace("#moj_import <minecraft:dynamictransforms.glsl>", "uniform mat4 ModelViewMat;")
                        .replace("#moj_import <minecraft:projection.glsl>", "uniform mat4 ProjMat;");
                var blocks = Pattern.compile("layout\\(std140\\) uniform \\w+\\s*\\{(.*?)\\};", Pattern.DOTALL).matcher(original);
                original = blocks.replaceAll(match -> {
                    String body = match.group(1).replaceAll("//[^\\n]*", "");
                    return java.util.Arrays.stream(body.split(";")).map(String::trim).filter(v -> !v.isEmpty())
                            .map(v -> "uniform " + v + ";").collect(java.util.stream.Collectors.joining("\n"));
                });
                String portable = resource(name + "_portable." + extension);
                assertEquals(original, portable, "only the uniform transport may change; preserve noise, corners, blending and UVs");
                var uniforms = Pattern.compile("uniform\\s+(mat4|vec[34])\\s+(\\w+)\\s*;").matcher(portable);
                while (uniforms.find()) assertTrue(declared.contains(uniforms.group(2)), uniforms.group(2));
            }
        }
    }

    private static Map<String, float[]> transport(String name, int bytes) {
        var result = new LinkedHashMap<String, float[]>();
        HudPortableUniforms.upload(new HudUniform() {
            public String getUBOName() { return name; }
            public int getUBOSize() { return bytes; }
            public void write(Std140BufferWriter writer) { throw new AssertionError("direct fixture serialization expected"); }
            public void write(ByteBuffer target) { for (int i = 1; target.remaining() >= 4; i++) target.putFloat(i); }
            public boolean shouldUseBuffer(HudUniform previous) { return false; }
        }, result::put);
        return result;
    }

    private static String resource(String name) throws Exception {
        try (var stream = HudPortableUniformsTest.class.getResourceAsStream("/assets/musichud_tuneweave/shaders/core/" + name)) {
            assertNotNull(stream, name);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
