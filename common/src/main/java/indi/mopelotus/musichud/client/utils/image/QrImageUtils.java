package indi.mopelotus.musichud.client.utils.image;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

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
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) QR_SIZE);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) QR_SIZE);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transcoder.transcode(
                    new TranscoderInput(new ByteArrayInputStream(svg)),
                    new TranscoderOutput(output));
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (Exception error) {
            throw new IllegalArgumentException("TuneWeave returned an invalid SVG QR image", error);
        }
    }

    private static String encode(String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new MultiFormatWriter().encode(
                    content, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE, hints);
            BufferedImage image = new BufferedImage(QR_SIZE, QR_SIZE, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < QR_SIZE; y++) {
                for (int x = 0; x < QR_SIZE; x++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
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
