package indi.mopelotus.musichud.client.utils.image;

import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.BitmapFactory;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** CPU-only bulk pixel round trips; deliberately never uses Bitmap's array pixel API. */
class ImageDownscaleBitmapTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAYAAAAECAYAAACtBE5DAAAAOklEQVR4nHWLsQ3AIBDEjMQS3uCGoGC0/GQZDQqUIgWlLbsBK1jBJziDb7A6wwZOcIEFhzvjL77gemwLtg6OPJkfuQAAAABJRU5ErkJggg==");

    @Test void fixedPngPreservesRgbaAlphaAndCenteredCrop() throws Exception {
        for (boolean crop : new boolean[]{false, true}) {
            var options = new BitmapFactory.Options();
            options.inPreferredFormat = Bitmap.Format.RGBA_8888;
            Bitmap source = BitmapFactory.decodeByteArray(PNG, 0, PNG.length, options);
            assertNotNull(source);
            int sw = crop ? 4 : 6;
            int[] input = new int[sw * 4];
            for (int y = 0; y < 4; y++) for (int x = 0; x < sw; x++) {
                int sx = x + (crop ? 1 : 0);
                int a = new int[]{255, 128, 0, 64}[(sx + 2 * y) % 4];
                input[y * sw + x] = a << 24 | sx * 40 << 16 | y * 60 << 8 | (sx + y) * 25;
            }
            int[] size = ImageDownscale.size(sw, 4, 3, 2);
            try (Bitmap result = ImageDownscale.resize(source, 3, 2, crop)) {
                assertTrue(source.isClosed());
                assertEquals(size[0], result.getWidth());
                assertEquals(size[1], result.getHeight());
                assertEquals(Bitmap.Format.RGBA_8888, result.getFormat());
                assertFalse(result.isPremultiplied());
                assertArrayEquals(ImageDownscale.filter(input, sw, 4, size[0], size[1]), read(result));
            }
        }
    }

    @Test void normalizesBgraAndPremultipliedStorageBeforeFiltering() {
        for (boolean premultiplied : new boolean[]{false, true}) {
            Bitmap source = Bitmap.createBitmap(4, 2, Bitmap.Format.BGRA_8888);
            try (source) {
                source.setPremultiplied(premultiplied);
                // Exactly representable straight RGBA = (128,64,32,128), or its premultiplied form.
                ByteBuffer bytes = MemoryUtil.memAlloc(32);
                try {
                    for (int i = 0; i < 8; i++) bytes.put((byte) (premultiplied ? 16 : 32))
                            .put((byte) (premultiplied ? 32 : 64)).put((byte) (premultiplied ? 64 : 128)).put((byte) 128);
                    bytes.flip();
                    assertTrue(source.copyPixelsFromBuffer(bytes, 16, 0, 0, 4, 2));
                } finally { MemoryUtil.memFree(bytes); }
                try (Bitmap result = ImageDownscale.resize(source, 1, 1, false)) {
                    assertArrayEquals(new int[]{0x80804020}, read(result));
                }
            }
        }
    }

    @Test void noResizeKeepsOwnershipAndFailureClosesSource() {
        try (Bitmap source = Bitmap.createBitmap(2, 2, Bitmap.Format.RGBA_8888)) {
            assertSame(source, ImageDownscale.resize(source, 4, 4, false));
            assertFalse(source.isClosed());
        }
        Bitmap source = Bitmap.createBitmap(2, 2, Bitmap.Format.RGBA_8888);
        assertThrows(IllegalArgumentException.class, () -> ImageDownscale.resize(source, -1, 1, false));
        assertTrue(source.isClosed());
    }

    private static int[] read(Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        // A padded row detects accidental assumptions about packed stride or buffer position.
        int rowBytes = (width + 1) * 4;
        ByteBuffer bytes = MemoryUtil.memAlloc(rowBytes * height);
        try {
            assertTrue(bitmap.copyPixelsToBuffer(bytes, rowBytes, 0, 0, width, height));
            int[] result = new int[width * height];
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                int offset = y * rowBytes + x * 4;
                result[y * width + x] = (bytes.get(offset + 3) & 255) << 24
                        | (bytes.get(offset) & 255) << 16 | (bytes.get(offset + 1) & 255) << 8
                        | (bytes.get(offset + 2) & 255);
            }
            return result;
        } finally { MemoryUtil.memFree(bytes); }
    }
}
