package indi.mopelotus.musichud.client.utils.lyrics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LyricsNormalizerTest {
    @Test void normalizesWhitespaceCommasAndNull() {
        assertEquals("A, B, C", LyricsNormalizer.normalize(" \tA,B,\u00a0 \r\nC "));
        assertEquals("", LyricsNormalizer.normalize(null));
        assertEquals("", LyricsNormalizer.normalize("\u00a0 \r\n"));
        assertEquals("📀, café", LyricsNormalizer.normalize("📀,café"));
    }

    @Test void boundaryMapIsMonotonicBoundedAndNeverChangesUnicode() {
        String raw = "  📀,\u00a0 \t中e\u0301  ";
        var normalized = LyricsNormalizer.withOffsets(raw);
        assertEquals("📀, 中e\u0301", normalized.text());
        int previous = 0;
        for (int i = 0; i <= raw.length(); i++) {
            int offset = normalized.offset(i);
            assertTrue(offset >= previous && offset <= normalized.text().length());
            previous = offset;
        }
        assertEquals(normalized.text().length(), normalized.offset(raw.length()));
        assertEquals(0, normalized.offset(2));
        assertEquals(2, normalized.offset(4));
    }
}
