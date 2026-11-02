package indi.mopelotus.musichud.companion.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class CompanionWireTest {
    private byte[] encode(CompanionWire.Message message) throws IOException {
        var bytes = new ByteArrayOutputStream();
        CompanionWire.write(new DataOutputStream(bytes), message);
        return bytes.toByteArray();
    }
    private CompanionWire.Message decode(byte[] bytes) throws IOException {
        return CompanionWire.read(new DataInputStream(new ByteArrayInputStream(bytes)));
    }
    @Test void preservesPixelsAndLockState() throws Exception {
        byte[] pixels = {12, 24, 30, 40, 0, 0, 0, 0};
        var result = decode(encode(new CompanionWire.Message(CompanionWire.Kind.FRAME, 7, 9, 13, false, 2, 1, pixels)));
        assertArrayEquals(pixels, result.rgba());
        assertEquals(7, result.generation());
        assertEquals(9, result.sequence());
        assertFalse(result.locked());
    }
    @Test void acceptsEmptyControls() throws Exception {
        for (var kind : new CompanionWire.Kind[]{CompanionWire.Kind.CLEAR, CompanionWire.Kind.STATUS}) {
            assertEquals(kind, decode(encode(new CompanionWire.Message(kind, 0, 0, 0, true, 0, 0, new byte[0]))).kind());
        }
    }
    @Test void rejectsEveryTruncation() throws Exception {
        byte[] frame = encode(new CompanionWire.Message(CompanionWire.Kind.FRAME, 1, 2, 3, true, 2, 1, new byte[8]));
        for (int size = 0; size < frame.length; size++) {
            byte[] truncated = Arrays.copyOf(frame, size);
            assertThrows(IOException.class, () -> decode(truncated));
        }
    }
    @Test void rejectsMalformedHeadersBeforeAllocatingPixels() throws Exception {
        byte[] valid = encode(new CompanionWire.Message(CompanionWire.Kind.FRAME, 1, 2, 3, true, 1, 1, new byte[4]));
        for (int offset : new int[]{0, 4, 6, 7, 8, 16, 32, 36, 40}) {
            byte[] bad = valid.clone();
            bad[offset] = (byte) 0xff;
            assertThrows(IOException.class, () -> decode(bad), "header offset " + offset);
        }
    }
    @Test void rejectsOverflowAndInvalidDimensions() {
        for (int[] size : new int[][]{{0, 1}, {-1, 3}, {8193, 1}, {8192, 8192}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            assertThrows(IllegalArgumentException.class, () -> CompanionWire.pixelBytes(size[0], size[1]));
        }
        assertEquals(32 * 1024 * 1024, CompanionWire.pixelBytes(8192, 1024));
    }
    @Test void rejectsPixelsInControlMessages() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionWire.Message(
                CompanionWire.Kind.CLEAR, 1, 1, 0, true, 1, 1, new byte[4]));
    }
}
