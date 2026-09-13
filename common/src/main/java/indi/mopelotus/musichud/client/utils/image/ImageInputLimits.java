package indi.mopelotus.musichud.client.utils.image;

import java.io.*;
import java.util.Base64;

public final class ImageInputLimits {
    public static final int MAX_BYTES = 20 * 1024 * 1024;
    private ImageInputLimits() {}
    public static byte[] read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new IOException("Image download is too large");
        return bytes;
    }
    public static void dimensions(int width, int height) throws IOException {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long)width * height > 4_194_304)
            throw new IOException("Image dimensions exceed limits");
    }
    public static byte[] base64(String data) {
        if (data == null || data.length() > MAX_BYTES * 4L / 3 + 1024) throw new IllegalArgumentException("Image data URL is too large");
        int comma = data.indexOf(',');
        if (comma < 0 || comma > 128 || !data.substring(0, comma).matches("data:image/[A-Za-z0-9.+-]+;base64"))
            throw new IllegalArgumentException("Invalid image data URL");
        byte[] bytes = Base64.getDecoder().decode(data.substring(comma + 1));
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("Image data URL is too large");
        return bytes;
    }
}
