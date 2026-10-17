package indi.mopelotus.musichud.client.utils.image;

import icyllis.modernui.graphics.Bitmap;

/** Aspect-preserving box filtering, adapted from MusicHud's premultiplied-alpha downscale. */
final class ImageDownscale {
    private ImageDownscale() {}

    /** Transfers ownership of source to the returned bitmap, closing it when replaced. */
    static Bitmap resize(Bitmap source, int maxWidth, int maxHeight, boolean squareCrop) {
        int originalWidth = source.getWidth(), originalHeight = source.getHeight();
        int sw = squareCrop ? Math.min(originalWidth, originalHeight) : originalWidth;
        int sh = squareCrop ? sw : originalHeight;
        if (maxWidth == 0 || maxHeight == 0 || (sw <= maxWidth && sh <= maxHeight)) return source;
        try (source) {
            int[] size = size(sw, sh, maxWidth, maxHeight);
            int[] input = new int[Math.multiplyExact(sw, sh)];
            source.getPixels(input, 0, sw, (originalWidth - sw) / 2, (originalHeight - sh) / 2, sw, sh);
            int[] pixels = filter(input, sw, sh, size[0], size[1]);
            Bitmap target = Bitmap.createBitmap(size[0], size[1], Bitmap.Format.RGBA_8888);
            try {
                target.setPixels(pixels, 0, size[0], 0, 0, size[0], size[1]);
                return target;
            } catch (RuntimeException | Error error) {
                target.close();
                throw error;
            }
        }
    }

    static int[] size(int sw, int sh, int maxWidth, int maxHeight) {
        if (sw < 1 || sh < 1 || maxWidth < 1 || maxHeight < 1) throw new IllegalArgumentException("Invalid image dimensions");
        double scale = Math.min(1, Math.min((double) maxWidth / sw, (double) maxHeight / sh));
        return new int[]{Math.max(1, (int) Math.round(sw * scale)), Math.max(1, (int) Math.round(sh * scale))};
    }

    static int[] filter(int[] input, int sw, int sh, int tw, int th) {
        if (sw < 1 || sh < 1 || tw < 1 || th < 1 || tw > sw || th > sh || (long) sw * sh != input.length)
            throw new IllegalArgumentException("Invalid image dimensions");
        int[] result = new int[Math.multiplyExact(tw, th)];
        for (int y = 0; y < th; y++) {
            int y0 = (int) ((long) y * sh / th), y1 = (int) ((long) (y + 1) * sh / th);
            for (int x = 0; x < tw; x++) {
                int x0 = (int) ((long) x * sw / tw), x1 = (int) ((long) (x + 1) * sw / tw);
                long a = 0, r = 0, g = 0, b = 0;
                for (int sy = y0; sy < y1; sy++) for (int sx = x0; sx < x1; sx++) {
                    int pixel = input[sy * sw + sx], alpha = pixel >>> 24;
                    a += alpha;
                    r += (long) ((pixel >>> 16) & 255) * alpha;
                    g += (long) ((pixel >>> 8) & 255) * alpha;
                    b += (long) (pixel & 255) * alpha;
                }
                if (a != 0) result[y * tw + x] = ((int) (a / ((long) (x1 - x0) * (y1 - y0))) << 24)
                        | ((int) (r / a) << 16) | ((int) (g / a) << 8) | (int) (b / a);
            }
        }
        return result;
    }
}
