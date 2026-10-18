package indi.mopelotus.musichud.client.utils.image;

import indi.mopelotus.musichud.client.utils.image.qrcode.QrCode;
import indi.mopelotus.musichud.client.utils.image.qrcode.QrSegment;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;

/** Normalizes TuneWeave QR payloads into a raster data URL ModernUI can display. */
public final class QrImageUtils {
    private static final int QR_SIZE = 640;

    private QrImageUtils() {
    }

    public static String prepare(String imageDataUrl, String qrContent) {
        if (imageDataUrl != null && !imageDataUrl.isBlank()) {
            String normalized = imageDataUrl.trim();
            if (normalized.regionMatches(true, 0, "data:image/svg+xml", 0, 17)) {
                return rasterizeSvg(normalized);
            }
            if (normalized.startsWith("data:image/")) {
                return normalized;
            }
            if (normalized.startsWith("https://") || normalized.startsWith("http://")) {
                return normalized;
            }
        }
        if (qrContent == null || qrContent.isBlank()) {
            throw new IllegalArgumentException("TuneWeave returned neither a QR image nor QR content");
        }
        if (qrContent.startsWith("data:image/")) {
            return prepare(qrContent, null);
        }
        return encode(qrContent);
    }

    private static String rasterizeSvg(String dataUrl) {
        try {
            byte[] svg = decodeDataUrl(dataUrl);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(
                    SvgImages.png(svg, QR_SIZE, QR_SIZE));
        } catch (Exception error) {
            throw new IllegalArgumentException("TuneWeave returned an invalid SVG QR image", error);
        }
    }

    private static String encode(String content) {
        try {
            // ECI makes non-ASCII URLs unambiguous to independent scanners. Keep the
            // numeric/alphanumeric modes for long tokens that fit more compactly.
            var segments = new ArrayList<QrSegment>();
            var payload = QrSegment.makeSegments(content);
            if (payload.stream().anyMatch(segment -> segment.mode == QrSegment.Mode.BYTE)) {
                segments.add(QrSegment.makeEci(26));
            }
            segments.addAll(payload);
            QrCode qr = QrCode.encodeSegments(segments, QrCode.Ecc.LOW, 1, 40, -1, false);
            int scale = QR_SIZE / (qr.size + 4); // Preserve the existing two-module quiet zone.
            int offset = (QR_SIZE - qr.size * scale) / 2;
            BufferedImage image = new BufferedImage(QR_SIZE, QR_SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < QR_SIZE; y++) {
                for (int x = 0; x < QR_SIZE; x++) {
                    int moduleX = (x - offset) / scale;
                    int moduleY = (y - offset) / scale;
                    boolean dark = x >= offset && y >= offset && qr.getModule(moduleX, moduleY);
                    image.setRGB(x, y, dark ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception error) {
            throw new IllegalArgumentException("TuneWeave QR content could not be encoded", error);
        }
    }

    private static byte[] decodeDataUrl(String dataUrl) {
        int comma = dataUrl.indexOf(',');
        if (comma <= 0) {
            throw new IllegalArgumentException("Malformed data URL");
        }
        String metadata = dataUrl.substring(0, comma).toLowerCase(java.util.Locale.ROOT);
        String payload = dataUrl.substring(comma + 1);
        if (metadata.endsWith(";base64")) {
            return Base64.getDecoder().decode(payload);
        }
        return URLDecoder.decode(payload, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8);
    }
}
