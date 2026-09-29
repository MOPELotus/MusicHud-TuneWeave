package indi.mopelotus.musichud.client.utils.image;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.ImageTranscoder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Shared resource decoder for both Minecraft HUD and ModernUI. Original assets stay unchanged; UI and HUD share white transparent marks. */
public final class PlatformIconData {
    private PlatformIconData() {}
    private static byte[] monochrome(byte[] bytes, boolean removePinkBackground) throws Exception {
        var source = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        var mask = new java.awt.image.BufferedImage(source.getWidth(), source.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) for (int x = 0; x < source.getWidth(); x++) {
            int color = source.getRGB(x, y), alpha = color >>> 24;
            if (removePinkBackground) {
                // Migu's original JPEG is a white mark on pink; preserve only the light mark and its antialiasing.
                int light = Math.min((color >>> 16) & 255, Math.min((color >>> 8) & 255, color & 255));
                alpha = Math.clamp((light - 96) * 255 / 159, 0, 255);
            }
            mask.setRGB(x, y, (alpha << 24) | 0x00ffffff);
        }
        var output = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(mask, "png", output);
        source.flush(); mask.flush(); return output.toByteArray();
    }
    public static byte[] png(TuneWeavePlatform platform, int size) throws Exception {
        String root = "/assets/musichud_tuneweave/textures/platforms/" + platform.apiName();
        try (InputStream png = MusicHud.class.getResourceAsStream(root + ".png")) {
            if (png != null) {
                byte[] bytes = png.readAllBytes();
                return monochrome(bytes, false);
            }
        }
        try (InputStream jpeg = MusicHud.class.getResourceAsStream(root + ".jpg")) {
            if (jpeg != null) {
                var bitmap = javax.imageio.ImageIO.read(jpeg);
                var output = new ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bitmap, "png", output);
                return monochrome(output.toByteArray(), true);
            }
        }
        try (InputStream svg = MusicHud.class.getResourceAsStream(root + ".svg")) {
            if (svg == null) return null;
            ImageTranscoder transcoder = new ImageTranscoder() {
                @Override public java.awt.image.BufferedImage createImage(int width, int height) {
                    return new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                }
                @Override public void writeImage(java.awt.image.BufferedImage image, TranscoderOutput output)
                        throws org.apache.batik.transcoder.TranscoderException {
                    try { javax.imageio.ImageIO.write(image, "png", output.getOutputStream()); }
                    catch (java.io.IOException error) { throw new org.apache.batik.transcoder.TranscoderException(error); }
                }
            };
            transcoder.addTranscodingHint(ImageTranscoder.KEY_WIDTH, (float) Math.clamp(size, 16, 128));
            transcoder.addTranscodingHint(ImageTranscoder.KEY_HEIGHT, (float) Math.clamp(size, 16, 128));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(svg), new TranscoderOutput(result));
            return monochrome(result.toByteArray(), false);
        }
    }
}
