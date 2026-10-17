package indi.mopelotus.musichud.client.utils.image;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class ImageUtilsInputTest {
    private byte[] read(String source) throws Exception {
        var method = ImageUtils.class.getDeclaredMethod("downloadImage", String.class, Function.class);
        method.setAccessible(true);
        Function<InputStream, byte[]> decoder = stream -> {
            try { return stream.readAllBytes(); } catch (java.io.IOException error) { throw new AssertionError(error); }
        };
        return (byte[]) method.invoke(null, source, decoder);
    }

    @Test void hudAndPreloadCanReadTheSameInlineArtworkAsUiCards() throws Exception {
        byte[] bytes = read(indi.mopelotus.musichud.MusicHud.ICON_BASE64);
        assertArrayEquals(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, java.util.Arrays.copyOf(bytes, 8));
    }

    @Test void malformedInlineSourcesCannotFallThroughToANetworkRequest() {
        for (String source : new String[]{"data:image/png;base64,%%%", "data:text/plain;base64,AA==", "data:image/png,raw"}) {
            var failure = assertThrows(InvocationTargetException.class, () -> read(source));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }
}
