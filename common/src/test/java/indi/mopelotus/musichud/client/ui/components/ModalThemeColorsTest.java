package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.R;
import icyllis.modernui.graphics.Color;
import icyllis.modernui.resources.*;
import icyllis.modernui.util.*;
import org.junit.jupiter.api.Test;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ModalThemeColorsTest {
    @Test void inlineThemeColorsUseActualArgbAndDisabledAlpha() {
        var colors = Modal.cardStrokeColors(theme(style -> {
            style.addColor(R.ns, R.attr.colorOutlineVariant, 0xFF123456);
            style.addColor(R.ns, R.attr.colorOutline, 0x80112233);
        }));
        assertEquals(Color.pack(0xFF123456), enabled(colors));
        assertEquals(0x0F112233, Color.toArgb(colors.getDefaultColor()));
        assertEquals((128f / 255) * 0.12f, Color.alpha(colors.getDefaultColor()), 0.0001f);
    }

    @Test void resourceSelectorsUseTheirDefaultColorAndPreservePackedColorSpace() {
        long enabled = Color.pack(0.2f, 0.4f, 0.6f, 0.8f,
                icyllis.arc3d.core.ColorSpace.get(icyllis.arc3d.core.ColorSpace.Named.DISPLAY_P3));
        long outline = Color.pack(0xC0336699);
        var colors = Modal.cardStrokeColors(theme(style -> {
            style.addColor(R.ns, R.attr.colorOutlineVariant, (resources, current) -> selector(enabled));
            style.addColor(R.ns, R.attr.colorOutline, (resources, current) -> selector(outline));
        }));
        assertEquals(enabled, enabled(colors));
        assertEquals(Color.withAlpha(outline, Color.alpha(outline) * 0.12f), colors.getDefaultColor());
    }

    @Test void missingAttributesFallbackIndependentlyAndTransparentColorRemainsValid() {
        var onlyVariant = Modal.cardStrokeColors(theme(style ->
                style.addColor(R.ns, R.attr.colorOutlineVariant, 0xFF123456)));
        assertEquals(Color.pack(0xFF123456), enabled(onlyVariant));
        assertEquals(Color.pack(0x08FFFFFF), onlyVariant.getDefaultColor());
        var onlyOutline = Modal.cardStrokeColors(theme(style ->
                style.addColor(R.ns, R.attr.colorOutline, 0x00000000)));
        assertEquals(Color.pack(0x20FFFFFF), enabled(onlyOutline));
        assertEquals(Color.pack(0x00000000), onlyOutline.getDefaultColor());
        var absent = Modal.cardStrokeColors(theme(style -> {}));
        assertEquals(Color.pack(0x20FFFFFF), enabled(absent));
        assertEquals(Color.pack(0x08FFFFFF), absent.getDefaultColor());
    }

    private static long enabled(ColorStateList colors) {
        return colors.getColorForState(new int[]{R.attr.state_enabled}, Color.pack(0));
    }

    private static ColorStateList selector(long color) {
        return new ColorStateList(new int[][]{new int[]{R.attr.state_pressed}, StateSet.WILD_CARD},
                new long[]{Color.pack(0xFFFF0000), color});
    }

    private static Resources.Theme theme(Consumer<ResourcesBuilder.Style> attributes) {
        ResourcesBuilder builder = new ResourcesBuilder("modaltest");
        attributes.accept(builder.newStyle("Card", null));
        ResourcesLoader loader = new ResourcesLoader();
        loader.addProvider(builder.build());
        Resources resources = new Resources(new AssetManager.Builder().addLoader(loader).build(),
                new DisplayMetrics(), new Configuration());
        Resources.Theme theme = resources.newTheme();
        theme.applyStyle(new ResourceId("modaltest", "style", "Card"), true);
        return theme;
    }
}
