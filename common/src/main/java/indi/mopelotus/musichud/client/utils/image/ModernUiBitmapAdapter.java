package indi.mopelotus.musichud.client.utils.image;

import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.BitmapFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** The Modern UI 3.12 API copies a bounded region instead of exposing subImage. */
public final class ModernUiBitmapAdapter {
    private ModernUiBitmapAdapter() {}

    public static Bitmap decodeBounded(byte[] bytes, BitmapFactory.Options options) throws IOException {
        var info = new BitmapFactory.Options();
        BitmapFactory.decodeStreamInfo(new ByteArrayInputStream(bytes), info);
        ImageInputLimits.dimensions(info.outWidth, info.outHeight);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    public static Bitmap crop(Bitmap source, int left, int top, int width, int height) {
        if (left < 0 || top < 0 || width <= 0 || height <= 0
                || left > source.getWidth() - width || top > source.getHeight() - height) {
            throw new IllegalArgumentException("Crop lies outside the source bitmap");
        }
        Bitmap result = Bitmap.createBitmap(width, height, source.getFormat(),
                source.isPremultiplied(), source.getColorSpace());
        try {
            source.getPixels(result, 0, 0, left, top, width, height);
            return result;
        } catch (RuntimeException | Error error) {
            result.close();
            throw error;
        }
    }
}
