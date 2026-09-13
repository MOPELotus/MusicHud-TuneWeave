package indi.mopelotus.musichud.client.ui.hud.pipelines;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class HudShaderImportsTest {
    @Test void expandsTheActualPackagedShadersWithoutChangingTheirBodies() throws Exception {
        for (String program : List.of("background", "album_image", "progress_bar")) {
            for (String extension : List.of("vsh", "fsh")) {
                try (var stream = getClass().getResourceAsStream("/assets/musichud_tuneweave/shaders/core/" + program + "." + extension)) {
                    assertNotNull(stream);
                    String source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    String expanded = HudShaderImports.expand(source, id -> switch (id) {
                        case "minecraft:dynamictransforms.glsl" -> "uniform mat4 ModelViewMat;";
                        case "minecraft:projection.glsl" -> "uniform mat4 ProjMat;";
                        default -> throw new AssertionError(id);
                    });
                    assertTrue(expanded.contains("void main()"));
                    assertFalse(expanded.contains("#moj_import"));
                }
            }
        }
    }
    @Test void malformedAndEscapingImportsAreRejectedBeforeResourceLookup() {
        for (String bad : List.of("#moj_import <missing_namespace>", "#moj_import <minecraft:../outside.glsl>",
                "#moj_import <minecraft:/absolute.glsl>", "#moj_import <minecraft:bad.glsl", "#moj_import <>")) {
            assertThrows(IllegalArgumentException.class, () -> HudShaderImports.expand(bad, id -> { throw new AssertionError(id); }));
        }
    }
    @Test void oversizedSourceAndImportExpansionAreRejected() {
        String tooLarge = "x".repeat(HudShaderImports.MAX_SOURCE_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> HudShaderImports.expand(tooLarge, id -> ""));
        assertThrows(IllegalArgumentException.class, () -> HudShaderImports.expand("#moj_import <test:large.glsl>", id -> tooLarge));
        assertThrows(IllegalArgumentException.class, () -> HudShaderImports.expand(
                "#moj_import <test:one.glsl>\n#moj_import <test:two.glsl>", id -> "x".repeat(600_000)));
    }
    @Test void nestedImportsAreRejectedAndReplacementMetacharactersRemainLiteral() {
        String source = "#moj_import <test:one.glsl>";
        assertThrows(IllegalArgumentException.class, () -> HudShaderImports.expand(source, id -> "#moj_import <test:two.glsl>"));
        assertEquals("// $1 \\ literal", HudShaderImports.expand(source, id -> "// $1 \\ literal"));
    }
}
