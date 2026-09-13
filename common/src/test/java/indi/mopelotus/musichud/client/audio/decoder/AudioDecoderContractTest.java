package indi.mopelotus.musichud.client.audio.decoder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AudioDecoderContractTest {
    @Test
    void existingDecoderContractDefaultsToSigned16() {
        AudioDecoder decoder = new AudioDecoder() {
            public byte[] readChunk(long size) { return null; }
            public int getFormat() { return 0; }
            public int getSampleRate() { return 44100; }
            public int getFrameSize() { return 4; }
            public void close() { }
        };
        assertEquals(AudioDecoder.SampleEncoding.PCM_S16_LE, decoder.getSampleEncoding());
    }
}
