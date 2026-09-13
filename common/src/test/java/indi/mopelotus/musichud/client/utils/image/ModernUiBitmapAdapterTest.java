package indi.mopelotus.musichud.client.utils.image;

import icyllis.modernui.graphics.Bitmap;
import icyllis.modernui.graphics.BitmapFactory;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModernUiBitmapAdapterTest {
    @Test
    void cropCopiesTheSelectedRegionWithoutOwningTheSource() {
        try (Bitmap source = Bitmap.createBitmap(4, 2, Bitmap.Format.RGBA_8888)) {
            int[] pixels = {0xff000000, 0xff112233, 0xff445566, 0xff777777,
                    0xff888888, 0xff99aabb, 0xffccddee, 0xffffffff};
            for (int index = 0; index < pixels.length; index++) {
                int argb = pixels[index];
                source.setColor4f(index % 4, index / 4, new float[]{
                        (argb >>> 16 & 255) / 255f, (argb >>> 8 & 255) / 255f,
                        (argb & 255) / 255f, 1f});
            }
            assertEquals(0xff112233, source.getPixelARGB(1, 0), "Source pixel before copy");
            try (Bitmap cropped = ModernUiBitmapAdapter.crop(source, 1, 0, 2, 2)) {
                assertEquals(2, cropped.getWidth());
                assertEquals(2, cropped.getHeight());
                assertEquals(0xff112233, cropped.getPixelARGB(0, 0));
                assertEquals(0xffccddee, cropped.getPixelARGB(1, 1));
                assertEquals(source.getColorSpace(), cropped.getColorSpace());
            }
            assertFalse(source.isClosed());
            assertEquals(0xff445566, source.getPixelARGB(2, 0));
        }
    }

    @Test
    void metadataProbeRejectsMalformedImagesAndOversizedDimensions() throws IOException {
        assertThrows(IOException.class, () -> ModernUiBitmapAdapter.decodeBounded(new byte[]{1, 2, 3},
                new BitmapFactory.Options()));
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", encoded);
        byte[] png = encoded.toByteArray();
        try (Bitmap decoded = ModernUiBitmapAdapter.decodeBounded(png, new BitmapFactory.Options())) {
            assertEquals(1, decoded.getWidth());
            assertEquals(1, decoded.getHeight());
        }
        ByteBuffer.wrap(png).putInt(16, 8193);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 17);
        ByteBuffer.wrap(png).putInt(29, (int) crc.getValue());
        IOException failure = assertThrows(IOException.class,
                () -> ModernUiBitmapAdapter.decodeBounded(png, new BitmapFactory.Options()));
        assertEquals("Image dimensions exceed limits", failure.getMessage(),
                "The metadata probe must reject before allocating decoded pixels");
    }

    @Test
    void cropRejectsInvalidAndOverflowingBoundsBeforeAllocating() {
        try (Bitmap source = Bitmap.createBitmap(2, 2, Bitmap.Format.RGBA_8888)) {
            for (int[] bounds : new int[][]{{-1, 0, 1, 1}, {0, -1, 1, 1}, {0, 0, 0, 1},
                    {0, 0, 1, -1}, {1, 0, 2, 1}, {0, 1, 1, 2}, {0, 0, Integer.MAX_VALUE, 1}}) {
                assertThrows(IllegalArgumentException.class, () -> ModernUiBitmapAdapter.crop(source,
                        bounds[0], bounds[1], bounds[2], bounds[3]));
            }
            assertFalse(source.isClosed());
        }
    }
}
