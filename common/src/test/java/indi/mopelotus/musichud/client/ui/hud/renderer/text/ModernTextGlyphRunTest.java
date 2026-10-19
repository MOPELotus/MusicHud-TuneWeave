package indi.mopelotus.musichud.client.ui.hud.renderer.text;

import icyllis.modernui.mc.text.CharacterStyle;
import icyllis.modernui.mc.text.GLBakedGlyph;
import icyllis.modernui.mc.text.GlyphManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModernTextGlyphRunTest {
    @Test void spacesUnavailableGlyphsAndEmptyObfuscatedSetsCannotOpenABatch() {
        GLBakedGlyph unavailable = glyph();
        unavailable.x = Integer.MIN_VALUE;
        GLBakedGlyph[] selected = ModernTextGeometry.selectDrawableGlyphs(
                new GLBakedGlyph[]{null, unavailable, new GlyphManager.FastCharSet()},
                new int[]{0, 0, CharacterStyle.OBFUSCATED_MASK});
        assertArrayEquals(new GLBakedGlyph[]{null, null, null}, selected);
        assertFalse(ModernTextGeometry.hasDrawableGlyph(selected, 0, selected.length));
    }

    @Test void fontAndEmojiBoundariesDoNotTurnAWhitespaceOnlySubrunIntoAnElement() {
        GLBakedGlyph outline = glyph(), emoji = glyph();
        GLBakedGlyph[] selected = ModernTextGeometry.selectDrawableGlyphs(
                new GLBakedGlyph[]{outline, null, emoji, null}, new int[4]);
        assertTrue(ModernTextGeometry.hasDrawableGlyph(selected, 0, 1));
        assertFalse(ModernTextGeometry.hasDrawableGlyph(selected, 1, 2));
        assertTrue(ModernTextGeometry.hasDrawableGlyph(selected, 2, 3));
        assertFalse(ModernTextGeometry.hasDrawableGlyph(selected, 3, 4));
        assertSame(outline, selected[0]);
        assertSame(emoji, selected[2]);
    }

    @Test void selectionRetainsPositionsAndChoosesObfuscatedGlyphOnlyOnceForThisSubmission() {
        GLBakedGlyph drawable = glyph();
        var choices = new GlyphManager.FastCharSet();
        choices.glyphs.add(drawable);
        GLBakedGlyph[] baked = {null, choices, null};
        GLBakedGlyph[] selected = ModernTextGeometry.selectDrawableGlyphs(baked,
                new int[]{0, CharacterStyle.OBFUSCATED_MASK, 0});
        choices.glyphs.clear();
        baked[1] = null;
        assertSame(drawable, selected[1]);
        assertNull(selected[0]);
        assertNull(selected[2]);
        assertTrue(ModernTextGeometry.hasDrawableGlyph(selected, 1, 2));
        // Keep the engine's live UV updates; do not freeze stale atlas coordinates.
        drawable.u1 = .25f;
        assertEquals(.25f, selected[1].u1);
    }

    private static GLBakedGlyph glyph() {
        GLBakedGlyph glyph = new GLBakedGlyph();
        glyph.x = 0;
        glyph.width = 8;
        glyph.height = 8;
        return glyph;
    }
}
