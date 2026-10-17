package indi.mopelotus.musichud.client.utils.image;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ImageDownscaleTest {
    @Test void transparentPixelsDoNotBleedColorIntoOpaqueEdges() {
        assertArrayEquals(new int[]{0x7fff0000}, ImageDownscale.filter(new int[]{0xffff0000, 0x000000ff}, 2, 1, 1, 1));
        assertArrayEquals(new int[]{0}, ImageDownscale.filter(new int[]{0x000000ff, 0x00ff0000}, 2, 1, 1, 1));
    }
    @Test void keepsAspectRatioAndNeverUpscales() {
        assertArrayEquals(new int[]{240, 120}, ImageDownscale.size(1920, 960, 240, 240));
        assertArrayEquals(new int[]{80, 40}, ImageDownscale.size(80, 40, 240, 240));
        assertArrayEquals(new int[]{1, 240}, ImageDownscale.size(1, 8192, 240, 240));
    }
    @Test void usesEveryPixelOfOddSizedSourcesAndRejectsInvalidBounds() {
        assertArrayEquals(new int[]{0xffff0000, 0xff007f7f},
                ImageDownscale.filter(new int[]{0xffff0000, 0xff00ff00, 0xff0000ff}, 3, 1, 2, 1));
        assertThrows(IllegalArgumentException.class, () -> ImageDownscale.filter(new int[1], Integer.MAX_VALUE, 2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> ImageDownscale.size(10, 10, -1, 5));
    }
}
