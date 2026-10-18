package indi.mopelotus.musichud.client.ui.hud.renderer.text;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GradientEmojiOpacityTest {
    @Test void translucentPlainTextPreservesTextureRgbAndItsOwnOpacity() {
        var gradient = GradientSpec.flat(0x55336699);
        assertEquals(0x55FFFFFF, gradient.sampleColorGlyph(0f, 1f));
        assertEquals(0x55FFFFFF, gradient.sampleColorGlyph(1f, 1f));
    }

    @Test void layoutFadeAndTextTransitionOpacityCanBeFoldedIntoTheFlatColor() {
        // TextRenderer folds its own transition and inherited layout opacity into the ARGB.
        for (float layoutAlpha : new float[]{0, .25f, .5f, 1}) {
            for (float textAlpha : new float[]{0, .25f, .5f, 1}) {
                int folded = (int) (208 * layoutAlpha * textAlpha);
                var gradient = GradientSpec.flat((folded << 24) | 0x123456);
                assertEquals(folded, gradient.sampleColorGlyph(.5f, 1f) >>> 24);
                assertEquals(0xFFFFFF, gradient.sampleColorGlyph(.5f, 1f) & 0xFFFFFF);
            }
        }
    }

    @Test void karaokeWindowSamplesBothEdgesOfTheSameEmojiIndependently() {
        var gradient = GradientSpec.window(0xD0336699, 0x55112233, .5f, .1f);
        assertEquals(0xD0FFFFFF, gradient.sampleColorGlyph(.4f, 1f));
        assertEquals(0x55FFFFFF, gradient.sampleColorGlyph(.6f, 1f));
        assertNotEquals(gradient.sampleColorGlyph(.4f, 1f), gradient.sampleColorGlyph(.6f, 1f));
        assertEquals((gradient.sample(.5f, .5f) & 0xFF000000) | 0xFFFFFF,
                gradient.sampleColorGlyph(.5f, .5f));
    }

    @Test void globalLayoutFadeMultipliesTheWindowOpacityIncludingFullyHiddenState() {
        var gradient = GradientSpec.window(0xD0336699, 0x55112233, .5f, .1f);
        for (float x : new float[]{0, .4f, .5f, .6f, 1}) {
            assertEquals(0x00FFFFFF, gradient.sampleColorGlyph(x, 0f));
            assertEquals(gradient.sample(x, .25f) >>> 24, gradient.sampleColorGlyph(x, .25f) >>> 24);
        }
    }
}
