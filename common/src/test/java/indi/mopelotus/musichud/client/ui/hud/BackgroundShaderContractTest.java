package indi.mopelotus.musichud.client.ui.hud;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundShaderContractTest {
    @Test
    void paletteSharesDriveWeightsAndMaskControlsOpacity() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/assets/musichud_tuneweave/shaders/core/background.fsh")) {
            String shader = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(shader.contains("u_Dark.a - 0.25"));
            assertTrue(shader.contains("fragColor = vec4(rgb, mask)"));
            assertTrue(!shader.contains("alpha * mask"));
        }
    }
}
