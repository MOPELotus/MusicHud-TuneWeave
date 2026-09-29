package indi.mopelotus.musichud.client.utils.image;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** Bridges WebP artwork to the PNG/JPEG decoder shared by the supported ModernUI versions. */
public final class WebpImages {
    private WebpImages() {}

    public static byte[] normalize(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length > ImageInputLimits.MAX_BYTES) {
            throw new IOException("Image data exceeds limits");
        }
        if (bytes.length < 12 || bytes[0] != 'R' || bytes[1] != 'I' || bytes[2] != 'F'
                || bytes[3] != 'F' || bytes[8] != 'W' || bytes[9] != 'E'
                || bytes[10] != 'B' || bytes[11] != 'P') {
            return bytes;
        }
        var reader = new WebPImageReaderSpi().createReaderInstance();
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            reader.setInput(input, true, true);
            // Inspect dimensions before allocating the decoded raster, including extended WebP.
            ImageInputLimits.dimensions(reader.getWidth(0), reader.getHeight(0));
            var image = reader.read(0);
            if (image == null) throw new IOException("WebP decoder returned no image");
            try {
                ImageInputLimits.dimensions(image.getWidth(), image.getHeight());
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
                if (output.size() > ImageInputLimits.MAX_BYTES) throw new IOException("Decoded image exceeds limits");
                return output.toByteArray();
            } finally {
                image.flush();
            }
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid WebP image", invalid);
        } finally {
            reader.dispose();
        }
    }
}
