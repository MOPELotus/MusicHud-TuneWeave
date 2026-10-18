package indi.mopelotus.musichud.client.utils.image;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/** Also runs against an isolated distribution JAR, with a full independent QR decoder. */
class QrImageUtilsTest {
    @Test void generatedQrImagesScanToTheExactOriginalPayload() throws Exception {
        for (String content : new String[]{"01234567890123456789", "ABCDEF 1234",
                "https://example.com/scan?key=abc&session=123",
                "https://example.com/scan?key=测试音乐🎵&session=123", "long-session-".repeat(100), "x".repeat(2952), "7".repeat(7089), "A".repeat(4296)}) {
            String normalized = prepare(null, content);
            assertTrue(normalized.startsWith("data:image/png;base64,"));
            var image = ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(normalized.split(",", 2)[1])));
            try {
                assertEquals(640, image.getWidth());
                assertEquals(640, image.getHeight());
                // The outer quiet zone must stay white even at the largest QR version.
                for (int i = 0; i < 640; i++) {
                    assertEquals(0xFFFFFFFF, image.getRGB(i, 0));
                    assertEquals(0xFFFFFFFF, image.getRGB(0, i));
                    assertEquals(0xFFFFFFFF, image.getRGB(i, 639));
                    assertEquals(0xFFFFFFFF, image.getRGB(639, i));
                }
                int[] pixels = image.getRGB(0, 0, 640, 640, null, 0, 640);
                var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(640, 640, pixels)));
                assertEquals(content, new QRCodeReader().decode(bitmap).getText());
            } finally { image.flush(); }
        }
    }

    @Test void invalidOrUnencodablePayloadsFailClearly() {
        assertThrows(IllegalArgumentException.class, () -> prepare(null, null));
        assertThrows(IllegalArgumentException.class, () -> prepare(null, " "));
        assertThrows(IllegalArgumentException.class, () -> prepare(null, ""));
        assertThrows(IllegalArgumentException.class, () -> prepare(null, "x".repeat(2953)));
        assertThrows(IllegalArgumentException.class, () -> prepare(null, "x".repeat(10000)));
    }

    private static String prepare(String image, String content) throws Exception {
        String artifact = System.getProperty("qr.distributionJar");
        if (artifact == null) return QrImageUtils.prepare(image, content);
        try (var loader = new URLClassLoader(new URL[]{Path.of(artifact).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            assertNotNull(loader.getResource("META-INF/licenses/nayuki-qrcode/LICENSE.txt"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.google.zxing.qrcode.QRCodeWriter"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("indi.mopelotus.musichud.shaded.zxing.qrcode.QRCodeWriter"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("indi.mopelotus.musichud.shaded.zxing.MultiFormatWriter"));
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("indi.mopelotus.musichud.shaded.zxing.qrcode.QRCodeReader"));
            Class<?> caller = loader.loadClass("indi.mopelotus.musichud.client.utils.image.QrImageUtils");
            try {
                return (String) caller.getMethod("prepare", String.class, String.class).invoke(null, image, content);
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof RuntimeException cause) throw cause;
                throw failure;
            }
        }
    }
}
