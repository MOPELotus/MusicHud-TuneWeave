package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.client.audio.decoder.AudioDecoder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlaybackDecoderSlotTest {
    @Test void lateOpenCannotReplaceNewDecoderAndIsClosed() {
        var slot = new PlaybackDecoderSlot(); slot.advance(1);
        var first = new Decoder(); assertTrue(slot.adopt(1, first));
        slot.advance(2); assertEquals(1, first.closes);
        var current = new Decoder(); assertTrue(slot.adopt(2, current));
        var late = new Decoder(); assertFalse(slot.adopt(1, late));
        assertEquals(1, late.closes); assertSame(current, slot.current()); assertEquals(0, current.closes);
        slot.advance(1); assertSame(current, slot.current());
        slot.advance(3); assertNull(slot.current()); assertEquals(1, current.closes);
    }

    @Test void backupReplacementClosesOnlyItsPredecessor() {
        var slot = new PlaybackDecoderSlot(); slot.advance(1);
        var first = new Decoder(); var backup = new Decoder();
        slot.adopt(1, first); slot.adopt(1, backup);
        assertEquals(1, first.closes); assertEquals(0, backup.closes);
        slot.adopt(1, backup); assertEquals(0, backup.closes);
        slot.advance(2); slot.advance(2); assertEquals(1, backup.closes);
    }

    private static final class Decoder implements AudioDecoder {
        int closes;
        public byte[] readChunk(long size) { return null; }
        public int getFormat() { return 0; }
        public int getSampleRate() { return 44100; }
        public int getFrameSize() { return 4; }
        public void close() { closes++; }
    }
}
