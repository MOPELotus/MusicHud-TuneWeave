package indi.mopelotus.musichud.client.utils.image;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class SvgImagesTest {
    @Test void gradientsAndTransparencySurviveScaling() throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">
                  <defs><linearGradient id="g"><stop stop-color="#ff0000"/>
                    <stop offset="1" stop-color="#0000ff"/></linearGradient></defs>
                  <rect x="4" y="4" width="8" height="8" fill="url(#g)"/>
                </svg>
                """;
        var image = ImageIO.read(new ByteArrayInputStream(SvgImages.png(bytes(svg), 64, 64)));
        try {
            assertEquals(64, image.getWidth());
            assertEquals(64, image.getHeight());
            assertEquals(0, image.getRGB(0, 0) >>> 24);
            int left = image.getRGB(20, 32), right = image.getRGB(44, 32);
            assertEquals(255, left >>> 24);
            assertTrue(((left >> 16) & 255) > (left & 255));
            assertTrue((right & 255) > ((right >> 16) & 255));
        } finally { image.flush(); }
    }

    @Test void serviceStyleSvgQrCodeStillScansToTheOriginalContent() throws Exception {
        String content = "https://example.com/scan?key=测试音乐&session=123";
        var matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 45, 45,
                Map.of(EncodeHintType.CHARACTER_SET, "UTF-8", EncodeHintType.MARGIN, 4));
        StringBuilder svg = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(matrix.getWidth()).append("\" height=\"").append(matrix.getHeight())
                .append("\"><rect width=\"100%\" height=\"100%\" fill=\"white\"/><path fill=\"black\" d=\"");
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++) {
            if (matrix.get(x, y)) svg.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
        }
        svg.append("\"/></svg>");
        String dataUrl = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(bytes(svg.toString()));
        String normalized = QrImageUtils.prepare(dataUrl, null);
        var image = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(normalized.split(",", 2)[1])));
        try {
            assertEquals(640, image.getWidth());
            assertEquals(640, image.getHeight());
            int[] pixels = image.getRGB(0, 0, 640, 640, null, 0, 640);
            var bitmap = new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(640, 640, pixels)));
            assertEquals(content, new QRCodeReader().decode(bitmap,
                    Map.of(DecodeHintType.CHARACTER_SET, "UTF-8")).getText());
        } finally { image.flush(); }
    }

    @Test void malformedInputsAndOversizedOutputAreRejectedBeforeAllocation() {
        assertThrows(IOException.class, () -> SvgImages.png(null, 16, 16));
        assertThrows(IOException.class, () -> SvgImages.png(new byte[0], 16, 16));
        assertThrows(IOException.class, () -> SvgImages.png(bytes("<svg>"), 16, 16));
        assertThrows(IOException.class, () -> SvgImages.png(bytes("<html/>"), 16, 16));
        assertThrows(IOException.class, () -> SvgImages.png(bytes("<svg/>"), 8193, 16));
        assertThrows(IOException.class, () -> SvgImages.png(bytes("<svg/>"), 4096, 4096));
        assertThrows(IOException.class, () -> SvgImages.png(new byte[ImageInputLimits.MAX_BYTES + 1], 16, 16));
    }

    @Test void externalImagesAndDtdNeverOpenConnections() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            try (exchange) { exchange.sendResponseHeaders(404, -1); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            SvgImages.png(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\">"
                    + "<image href=\"" + base + "/image.png\" width=\"16\" height=\"16\"/></svg>"), 16, 16);
            SvgImages.png(bytes("<!DOCTYPE svg SYSTEM \"" + base + "/external.dtd\">"
                    + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\"/>"), 16, 16);
            assertEquals(0, requests.get());
        } finally { server.stop(0); }
    }

    @Test void compressedSvgUsesTheSameLimitForExpandedXml() throws Exception {
        byte[] valid = compressed(bytes("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"16\" height=\"16\"><rect width=\"16\" height=\"16\" fill=\"red\"/></svg>"));
        var image = ImageIO.read(new ByteArrayInputStream(SvgImages.png(valid, 16, 16)));
        try { assertEquals(0xffff0000, image.getRGB(8, 8)); }
        finally { image.flush(); }
        byte[] oversized = compressed(new byte[ImageInputLimits.MAX_BYTES + 1]);
        assertTrue(oversized.length < ImageInputLimits.MAX_BYTES);
        assertThrows(IOException.class, () -> SvgImages.png(oversized, 16, 16));
    }

    private static byte[] compressed(byte[] input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(input); }
        return bytes.toByteArray();
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
