/*
 * Modern UI. Licensed under LGPL-3.0-or-later; see LICENSE.
 */
package indi.mopelotus.musichud.client.utils.text;

import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.pipeline.ArcCanvas;
import icyllis.modernui.graphics.text.CharUtils;
import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.graphics.text.Font;
import icyllis.modernui.graphics.text.FontPaint;
import icyllis.modernui.graphics.text.ShapedText;
import icyllis.modernui.mc.text.GlyphManager;

/** Adds the resource-pack image fonts which Arc3D's native text blobs cannot represent. */
public final class EmojiAwareCanvas extends ArcCanvas {
    private final EmojiImages images;

    EmojiAwareCanvas(icyllis.arc3d.sketch.Canvas canvas, EmojiImages images) {
        super(canvas);
        this.images = images;
    }

    public static EmojiAwareCanvas create(icyllis.arc3d.sketch.Canvas canvas) {
        return new EmojiAwareCanvas(canvas, WidgetEmojiResources.forCanvas());
    }

    @Override
    public void drawShapedText(ShapedText text, float x, float y, Paint paint) {
        super.drawShapedText(text, x, y, paint);
        Paint imagePaint = imagePaint(paint);
        try {
            for (int i = 0; i < text.getGlyphCount(); i++) {
                if (text.getFont(i) instanceof EmojiFont font) {
                    drawEmoji(font, text.getGlyph(i), x + text.getX(i), y + text.getY(i),
                            imagePaint, text.getPaint(), paint);
                }
            }
        } finally {
            imagePaint.reset();
        }
    }

    @Override
    public void drawTextRun(char[] text, int start, int end, int contextStart, int contextEnd,
                            float x, float y, boolean isRtl, Paint paint, FontPaint fontPaint) {
        // The native implementation draws only outline fonts. Keep its batching and layout unchanged.
        super.drawTextRun(text, start, end, contextStart, contextEnd, x, y, isRtl, paint, fontPaint);
        if (start == end) return;
        Paint imagePaint = imagePaint(paint);
        try {
            ShapedText.doLayoutRun(text, contextStart, contextEnd, start, end, isRtl, fontPaint, null,
                    (piece, a, b, rtl, piecePaint, offsetX) -> {
                        for (int i = 0; i < piece.getGlyphCount(); i++) {
                            if (piece.getFont(i) instanceof EmojiFont font) {
                                drawEmoji(font, piece.getGlyphs()[i], x + offsetX + piece.getPositions()[i * 2],
                                        y + piece.getPositions()[i * 2 + 1], imagePaint, piecePaint, paint);
                            }
                        }
                    });
        } finally {
            imagePaint.reset();
        }
    }

    @Override
    public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd,
                            float x, float y, boolean isRtl, Paint paint, FontPaint fontPaint) {
        if ((start | end | contextStart | contextEnd | start - contextStart | end - start |
                contextEnd - end | text.length() - contextEnd) < 0) throw new IndexOutOfBoundsException();
        if (start == end) return;
        int length = contextEnd - contextStart;
        char[] buffer = CharUtils.obtain(length);
        try {
            CharUtils.getChars(text, contextStart, contextEnd, buffer, 0);
            // Exactly one image pass, regardless of the core's CharSequence delegation strategy.
            drawTextRun(buffer, start - contextStart, end - contextStart, 0, length,
                    x, y, isRtl, paint, fontPaint);
        } finally {
            CharUtils.recycle(buffer);
        }
    }

    @Override
    public void drawGlyphs(int[] glyphs, int glyphOffset, float[] positions, int positionOffset,
                           int glyphCount, Font font, float x, float y, Paint paint, FontPaint fontPaint) {
        if (!(font instanceof EmojiFont emoji)) {
            super.drawGlyphs(glyphs, glyphOffset, positions, positionOffset, glyphCount, font,
                    x, y, paint, fontPaint);
            return;
        }
        java.util.Objects.checkFromIndexSize(glyphOffset, glyphCount, glyphs.length);
        java.util.Objects.checkFromIndexSize(positionOffset, Math.multiplyExact(glyphCount, 2), positions.length);
        Paint imagePaint = imagePaint(paint);
        try {
            for (int i = 0; i < glyphCount; i++) {
                drawEmoji(emoji, glyphs[glyphOffset + i], x + positions[positionOffset + i * 2],
                        y + positions[positionOffset + i * 2 + 1], imagePaint, fontPaint, paint);
            }
        } finally {
            imagePaint.reset();
        }
    }

    private static Paint imagePaint(Paint textPaint) {
        Paint paint = new Paint(textPaint);
        // Color glyphs keep their original RGB. Preserve opacity and compositing, not the text shader.
        paint.setShader(null);
        paint.setColorFilter(null);
        paint.setColor4f(1, 1, 1, textPaint.getAlphaF());
        paint.setStyle(Paint.FILL);
        paint.setFilter(true);
        return paint;
    }

    private void drawEmoji(EmojiFont font, int glyph, float x, float y, Paint paint, FontPaint fontPaint, Paint textPaint) {
        var image = images.get(font, glyph);
        if (image == null) return;
        float size = GlyphManager.EMOJI_SIZE / (float) GlyphManager.EMOJI_BASE * fontPaint.getFontSize();
        float ascent = GlyphManager.EMOJI_ASCENT / (float) GlyphManager.EMOJI_BASE * fontPaint.getFontSize();
        if (!fontPaint.isLinearMetrics()) {
            size = (int) (0.95d + size);
            ascent = (int) (0.95d + ascent);
        }
        // Layout positions already contain the font's spacing; adding it again shifts every emoji.
        if (!textPaint.hasShader()) {
            drawImage(image, 0, 0, image.getWidth(), image.getHeight(), x, y - ascent,
                    x + size, y - ascent + size, paint);
        } else {
            // A colored image ignores drawImage's text shader. Compose only its alpha with the PNG,
            // in the original widget coordinates, so karaoke fade/highlight never tints emoji RGB.
            var nativePaint = paint.getNativePaint();
            var bounds = icyllis.arc3d.sketch.shaders.ImageShader.preparePaintForDrawImageRect(
                    image.getNativeImage(), icyllis.arc3d.core.SamplingOptions.LINEAR,
                    new icyllis.arc3d.core.Rect2f(0, 0, image.getWidth(), image.getHeight()),
                    new icyllis.arc3d.core.Rect2f(x, y - ascent, x + size, y - ascent + size),
                    false, nativePaint);
            nativePaint.setShader(icyllis.arc3d.sketch.shaders.BlendShader.make(
                    icyllis.arc3d.sketch.BlendMode.DST_IN,
                    textPaint.getNativePaint().refShader(), nativePaint.refShader()));
            try {
                getCanvas().drawRect(bounds, nativePaint);
            } finally {
                nativePaint.setShader(null);
            }
        }
    }
}
