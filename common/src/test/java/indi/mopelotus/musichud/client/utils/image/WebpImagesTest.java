package indi.mopelotus.musichud.client.utils.image;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class WebpImagesTest {
    private byte[] fixture(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/images/" + name)) {
            return stream.readAllBytes();
        }
    }

    @Test void losslessPreservesDimensionsColorAndTransparency() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(normalize(fixture("alpha.webp"))));
        assertEquals(13, image.getWidth());
        assertEquals(7, image.getHeight());
        assertEquals(0x801464dc, image.getRGB(0, 0));
        assertEquals(0xfff0280a, image.getRGB(2, 3));
    }

    @Test void lossyWebpProducesDecodableOpaqueImage() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(normalize(fixture("lossy.webp"))));
        assertEquals(13, image.getWidth());
        assertEquals(7, image.getHeight());
        assertEquals(255, image.getRGB(0, 0) >>> 24);
        assertTrue((image.getRGB(0, 0) & 255) > 170);
    }

    @Test void colorProfileExifAndXmpDoNotLoseAlphaOrPixels() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(normalize(fixture("profile.webp"))));
        try {
            assertEquals(32, image.getWidth()); assertEquals(24, image.getHeight());
            assertEquals(0x801464dc, image.getRGB(4, 4));
            assertEquals(0xfff0280a, image.getRGB(24, 4));
        } finally { image.flush(); }
    }

    @Test void lossyWithAlphaPreservesTransparencyAndColor() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(normalize(fixture("alpha-lossy.webp"))));
        try {
            assertEquals(32, image.getWidth()); assertEquals(24, image.getHeight());
            int blue = image.getRGB(4, 4), red = image.getRGB(24, 4);
            assertEquals(128, blue >>> 24); assertEquals(255, red >>> 24);
            assertTrue((blue & 255) > 200); assertTrue(((red >>> 16) & 255) > 220);
        } finally { image.flush(); }
    }

    @Test void animatedArtworkKeepsExistingFirstFrameBehavior() throws Exception {
        var image = ImageIO.read(new ByteArrayInputStream(normalize(fixture("animated.webp"))));
        try {
            assertEquals(32, image.getWidth()); assertEquals(24, image.getHeight());
            assertEquals(0x801464dc, image.getRGB(4, 4));
            assertEquals(0xfff0280a, image.getRGB(24, 4));
        } finally { image.flush(); }
    }

    @Test void existingFormatsAreNotReencoded() throws Exception {
        byte[] png = normalize(fixture("alpha.webp"));
        assertSame(png, normalize(png));
        byte[] unknown = {1, 2, 3};
        assertSame(unknown, normalize(unknown));
    }

    @Test void truncatedAndMalformedWebpAreRejected() throws Exception {
        byte[] valid = fixture("alpha.webp");
        for (int size : new int[]{12, 16, 20, 25}) {
            assertThrows(IOException.class, () -> normalize(Arrays.copyOf(valid, size)));
        }
        byte[] invalid = valid.clone();
        Arrays.fill(invalid, 12, invalid.length, (byte) 0);
        assertThrows(IOException.class, () -> normalize(invalid));
    }

    @Test void inputByteLimitIsEnforcedBeforeDecoding() {
        assertThrows(IOException.class, () -> normalize(null));
        assertThrows(IOException.class, () -> normalize(new byte[ImageInputLimits.MAX_BYTES + 1]));
    }

    @Test void extendedCanvasDimensionsAreRejectedBeforeRasterAllocation() {
        for (int[] dimensions : new int[][]{{8193, 1}, {1, 8193}, {4096, 4096}}) {
            byte[] bytes = new byte[30];
            var header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            header.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(22);
            header.put(new byte[]{'W', 'E', 'B', 'P', 'V', 'P', '8', 'X'}).putInt(10);
            int width = dimensions[0] - 1, height = dimensions[1] - 1;
            for (int i = 0; i < 3; i++) {
                bytes[24 + i] = (byte) (width >>> (i * 8));
                bytes[27 + i] = (byte) (height >>> (i * 8));
            }
            IOException error = assertThrows(IOException.class, () -> normalize(bytes));
            assertEquals("Image dimensions exceed limits", error.getMessage());
        }
    }
    private static byte[] normalize(byte[] input) throws IOException {
        String artifact = System.getProperty("webp.distributionJar");
        if (artifact == null) return WebpImages.normalize(input);
        try (var loader = new URLClassLoader(new URL[]{Path.of(artifact).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> caller = loader.loadClass("indi.mopelotus.musichud.client.utils.image.WebpImages");
            try {
                return (byte[]) caller.getMethod("normalize", byte[].class).invoke(null, new Object[]{input});
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof IOException cause) throw cause;
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw new IOException("Packaged WebP decoder failed", failure.getCause());
            }
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Packaged WebP decoder unavailable", failure);
        }
    }

}
