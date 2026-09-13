package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.client.audio.decoder.PcmFloat32Converter;
import org.junit.jupiter.api.Test;
import java.nio.*;
import static org.junit.jupiter.api.Assertions.*;

class PcmFloat32ConverterTest {
    @Test
    void converts24BitSignedSamplesAndPreservesChannelSampleCount() {
        byte[] pcm = {0, 0, 0, (byte) 0xff, (byte) 0xff, 0x7f, 0, 0, (byte) 0x80};
        ByteBuffer out = ByteBuffer.wrap(PcmFloat32Converter.from24Le(pcm)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0f, out.getFloat(), 0.00001f);
        assertEquals(1f - 1f / 8388608f, out.getFloat(), 0.00001f);
        assertEquals(-1f, out.getFloat(), 0.00001f);
    }

    @Test
    void converts32BitAndRejectsMisalignedInput() {
        ByteBuffer pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(Integer.MIN_VALUE).putInt(Integer.MAX_VALUE);
        ByteBuffer out = ByteBuffer.wrap(PcmFloat32Converter.from32Le(pcm.array())).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(-1f, out.getFloat(), 0.00001f);
        assertEquals(1f - 1f / 2147483648f, out.getFloat(), 0.00001f);
        assertThrows(IllegalArgumentException.class, () -> PcmFloat32Converter.from24Le(new byte[1]));
    }
}
