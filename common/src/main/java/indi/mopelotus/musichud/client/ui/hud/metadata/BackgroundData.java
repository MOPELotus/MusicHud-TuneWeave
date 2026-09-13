package indi.mopelotus.musichud.client.ui.hud.metadata;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import indi.mopelotus.musichud.client.ui.hud.pipelines.HudUniform;
import indi.mopelotus.musichud.client.utils.ui.ColorExtractor;
import indi.mopelotus.musichud.client.utils.ui.Easing;
import indi.mopelotus.musichud.client.utils.ui.Mixable;
import indi.mopelotus.musichud.client.utils.ui.UniformDataUtils;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import lombok.EqualsAndHashCode;

import java.util.Objects;

@EqualsAndHashCode
public final class BackgroundData implements Mixable<BackgroundData>, HudUniform {
    private final BackgroundImages image;
    private final ThemedColors themedColors;
    private ThemedColors mixedColors;
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private float mixAlpha;
    public static final BackgroundData NONE = new BackgroundData(null, ColorExtractor.getDefaultColors());

    public BackgroundData(
            BackgroundImages image
    ) {
        this.image = image;
        this.mixAlpha = (float) clientConfig.getHudBackgroundMixAlpha();
        this.themedColors = ColorExtractor.extractColors(image.current.getTexture());
        this.mixedColors = ColorExtractor.mixBaseColorsWithAlpha(themedColors, 0xFF1A1A1A, mixAlpha);
    }

    public BackgroundData(BackgroundImages image, ThemedColors themedColors) {
        this.image = image;
        this.mixAlpha = (float) clientConfig.getHudBackgroundMixAlpha();
        this.themedColors = themedColors;
        this.mixedColors = ColorExtractor.mixBaseColorsWithAlpha(themedColors, 0xFF1A1A1A, mixAlpha);
    }

    public ThemedColors color() {
        return themedColors;
    }

    public BackgroundImages image() {
        return image;
    }

    @Override
    public String toString() {
        return "BackgroundData[" +
                "themedColors=" + themedColors + ", " +
                "image=" + image + ']';
    }

    @Override
    public BackgroundData mix(BackgroundData next, float transitionProgress) {
        return new BackgroundData(image, next != null ? mixColor(next.themedColors, transitionProgress) : themedColors);
    }

    private ThemedColors mixColor(ThemedColors next, float t) {
        if (t <= 0.01f) return new ThemedColors(themedColors.primary, themedColors.secondary, themedColors.bright, themedColors.dark);
        if (t >= 0.99f) return new ThemedColors(next.primary, next.secondary, next.bright, next.dark);

        int c1 = mixRgbLerpAlpha(themedColors.primary, next.primary, t);
        int c2 = mixRgbLerpAlpha(themedColors.secondary, next.secondary, t);
        int c3 = mixRgbLerpAlpha(themedColors.bright, next.bright, t);
        int c4 = mixRgbLerpAlpha(themedColors.dark, next.dark, t);
        float sum = ((c1 >>> 24) & 255) + ((c2 >>> 24) & 255)
                + ((c3 >>> 24) & 255) + ((c4 >>> 24) & 255);
        if (sum > 0) {
            int[] a = ColorExtractor.sharesToAlphaBytes(
                    ((c1 >>> 24) & 255) / sum, ((c2 >>> 24) & 255) / sum,
                    ((c3 >>> 24) & 255) / sum, ((c4 >>> 24) & 255) / sum);
            c1 = (a[0] << 24) | (c1 & 0xFFFFFF);
            c2 = (a[1] << 24) | (c2 & 0xFFFFFF);
            c3 = (a[2] << 24) | (c3 & 0xFFFFFF);
            c4 = (a[3] << 24) | (c4 & 0xFFFFFF);
        }
        return new ThemedColors(c1, c2, c3, c4);
    }

    private static int mixRgbLerpAlpha(int a, int b, float t) {
        float eased = Easing.EASE_IN_OUT_QUAD.getInterpolation(t);
        int rgb = UniformDataUtils.interpolateARGB(0xFF000000 | (a & 0xFFFFFF),
                0xFF000000 | (b & 0xFFFFFF), eased) & 0xFFFFFF;
        int alpha = Math.clamp(Math.round(((a >>> 24) & 255)
                + (((b >>> 24) & 255) - ((a >>> 24) & 255)) * eased), 1, 255);
        return (alpha << 24) | rgb;
    }

    public static final int UBO_SIZE = new Std140SizeCalculator().putVec4().putVec4().putVec4().putVec4().align(16).get();

    @Override
    public String getUBOName() {
        return "MHNowPlayingThemeColor";
    }

    @Override
    public int getUBOSize() {
        return UBO_SIZE;
    }

    @Override
    public void write(Std140Builder builder) {
        builder.putVec4(UniformDataUtils.colorToVector(mixedColors.primary));
        builder.putVec4(UniformDataUtils.colorToVector(mixedColors.secondary));
        builder.putVec4(UniformDataUtils.colorToVector(mixedColors.bright));
        builder.putVec4(UniformDataUtils.colorToVector(mixedColors.dark));
    }

    @Override
    public boolean shouldUseBuffer(HudUniform lastBuffered) {
        if (lastBuffered instanceof BackgroundData data) {
            float mixAlpha = (float) clientConfig.getHudBackgroundMixAlpha();
            if (mixAlpha != this.mixAlpha) {
                this.mixAlpha = mixAlpha;
                mixedColors = ColorExtractor.mixBaseColorsWithAlpha(themedColors, 0xFF1A1A1A, mixAlpha);
                return false;
            } else {
                return Objects.equals(mixedColors, data.mixedColors);
            }
        } else {
            return false;
        }
    }
}
