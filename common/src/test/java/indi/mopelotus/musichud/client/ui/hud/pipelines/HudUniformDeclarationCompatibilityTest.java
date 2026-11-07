package indi.mopelotus.musichud.client.ui.hud.pipelines;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class HudUniformDeclarationCompatibilityTest {
    @Test void uniformBodiesUseTheFieldGrammarAcceptedByVulkanMod068() throws Exception {
        // Its published parser skips spacing but treats inline comments as fields.
        // Keep explanations outside uniform blocks; this changes no shader math or ABI.
        var blocks = Pattern.compile("layout\\(std140\\)\\s+uniform\\s+\\w+\\s*\\{(.*?)\\};", Pattern.DOTALL);
        int count = 0;
        for (String name : new String[]{"background.vsh", "background.fsh", "album_image.vsh", "album_image.fsh",
                "progress_bar.vsh", "progress_bar.fsh", "text_gradient.fsh"}) {
            try (var in = getClass().getResourceAsStream("/assets/musichud_tuneweave/shaders/core/" + name)) {
                assertNotNull(in, name);
                var matcher = blocks.matcher(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                while (matcher.find()) {
                    count++;
                    assertTrue(matcher.group(1).matches("(?s)(?:\\s*(?:mat4|vec[234]|float|int)\\s+\\w+\\s*;)+\\s*"),
                            name + ": VulkanMod requires plain field declarations inside uniform blocks");
                }
            }
        }
        assertEquals(12, count, "all shared vertex/fragment uniform declarations must be checked");
    }
}
