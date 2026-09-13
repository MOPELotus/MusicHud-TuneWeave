package indi.mopelotus.musichud.client.utils.image;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageInputLimitsTest {
    @Test void rejectsOversizedAndMalformedImageInputsBeforeAllocationOfPixels() throws Exception {
        ImageInputLimits.dimensions(2048, 2048);
        for (int[] size : List.of(new int[]{0, 1}, new int[]{1, -1}, new int[]{8193, 1}, new int[]{4096, 4096}, new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE}))
            assertThrows(IOException.class, () -> ImageInputLimits.dimensions(size[0], size[1]));
        assertArrayEquals(new byte[]{1, 2, 3}, ImageInputLimits.base64("data:image/png;base64,AQID"));
        for (String value : List.of("", "data:text/plain;base64,AQID", "data:image/png,AQID", "data:image/png;base64,%%%"))
            assertThrows(IllegalArgumentException.class, () -> ImageInputLimits.base64(value));
        var endless = new InputStream() {
            public int read() { return 0; }
            public int read(byte[] bytes, int offset, int length) { Arrays.fill(bytes, offset, offset + length, (byte)0); return length; }
        };
        assertThrows(IOException.class, () -> ImageInputLimits.read(endless));
    }
    @Test void imageTransportCannotOpenLocalFilesOrPrivateHttpEndpoints() {
        assertThrows(IOException.class, () -> indi.mopelotus.musichud.client.audio.decoder.PcmAudioInput.openPublicMedia("file:///private.png", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> indi.mopelotus.musichud.client.audio.decoder.PcmAudioInput.openPublicMedia("http://127.0.0.1/private.png", Map.of()));
    }
}
