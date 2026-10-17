package indi.mopelotus.musichud.client.utils.image;

import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.ViewBox;

import javax.imageio.ImageIO;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/** Rasterizes local SVG icons and service-provided QR images without external I/O. */
public final class SvgImages {
    private static final LoaderContext CONTEXT = LoaderContext.builder()
            .externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL)
            .build();

    private SvgImages() {}

    public static byte[] png(byte[] svg, int width, int height) throws IOException {
        ImageInputLimits.dimensions(width, height);
        if (svg == null || svg.length == 0 || svg.length > ImageInputLimits.MAX_BYTES) {
            throw new IOException("SVG data exceeds limits or is empty");
        }
        // JSVG accepts SVGZ too. Bound the expanded XML before handing it to the parser.
        if (svg.length >= 2 && svg[0] == 0x1f && svg[1] == (byte) 0x8b) {
            try (var compressed = new GZIPInputStream(new ByteArrayInputStream(svg))) {
                svg = ImageInputLimits.read(compressed);
            }
        }
        // SVGLoader is not thread-safe; only its immutable context is shared.
        var document = new SVGLoader().load(new ByteArrayInputStream(svg), null, CONTEXT);
        if (document == null || !Float.isFinite(document.size().width)
                || !Float.isFinite(document.size().height)
                || document.size().width <= 0 || document.size().height <= 0) {
            throw new IOException("Invalid SVG image");
        }
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            document.render(null, graphics, new ViewBox(width, height));
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG encoder unavailable");
            return output.toByteArray();
        } finally {
            graphics.dispose();
            image.flush();
        }
    }
}
