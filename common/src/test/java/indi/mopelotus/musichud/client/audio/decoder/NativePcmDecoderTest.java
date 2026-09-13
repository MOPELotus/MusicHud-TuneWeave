package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.beans.music.FormatType;
import indi.mopelotus.musichud.client.audio.OpenAlFormatSelector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NativePcmDecoderTest {
    @TempDir Path directory;

    @Test void ieeeFloatWaveSanitizesNonFiniteSamplesAndCloseIsIdempotent() throws Exception {
        ByteBuffer raw = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0, .5f, -1, 1, Float.NaN, Float.POSITIVE_INFINITY, -2, 2}) raw.putFloat(value);
        byte[] bytes = wave(2, 32, raw.array(), false);
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short)3);
        class Input extends ByteArrayInputStream {
            int closes;
            Input() { super(bytes); }
            public void close() { closes++; }
        }
        Input input = new Input();
        var decoder = new WavPcmDecoder(input, true);
        ByteBuffer result = ByteBuffer.wrap(decoder.readChunk(64)).order(ByteOrder.LITTLE_ENDIAN);
        for (float expected : new float[]{0, .5f, -1, 1, 0, 0, -1, 1}) assertEquals(expected, result.getFloat());
        decoder.close(); decoder.close();
        assertEquals(1, input.closes); assertNull(decoder.readChunk(64));
    }

    @Test void positionCountsOutputFramesAndPublicInputCannotReadLocalFiles() throws Exception {
        byte[] wave = wave(2, 24, new byte[480 * 6], false);
        try (var decoder = new WavPcmDecoder(new ByteArrayInputStream(wave), true)) {
            decoder.readChunk(480 * 8);
            assertEquals(10, decoder.getPositionMillis());
            assertEquals(8, decoder.getFrameSize());
            assertNull(decoder.readChunk(64));
        }
        assertThrows(IOException.class, () -> PcmAudioInput.open("file:///private", Map.of(), true));
        assertThrows(IllegalArgumentException.class, () -> PcmAudioInput.open("http://127.0.0.1/private", Map.of(), true));
    }

    @Test void waveNativeDepthSurvivesOddChunkBoundariesAndFallback() throws Exception {
        for (int channels : new int[]{1, 2}) for (int bits : new int[]{16, 24, 32}) for (boolean floating : new boolean[]{false, true}) {
            byte[] raw = samples(bits);
            Path file = directory.resolve("pcm-" + channels + "-" + bits + ".wav");
            Files.write(file, wave(channels, bits, raw, true));
            assertEquals("WavPcmDecoder", AudioDecoderFactory.probe(file.toString(), FormatType.WAV).backend());
            try (AudioDecoder decoder = AudioDecoderFactory.open(file.toString(), FormatType.WAV, Map.of(), floating)) {
                assertInstanceOf(WavPcmDecoder.class, decoder);
                assertEquals(OpenAlFormatSelector.select(channels, floating && bits > 16), decoder.getFormat());
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] chunk;
                while ((chunk = decoder.readChunk(17)) != null) {
                    assertEquals(0, chunk.length % decoder.getFrameSize()); assertTrue(chunk.length <= 17); result.write(chunk);
                }
                assertArrayEquals(new PcmOutput(channels, bits, 48000, floating).convert(raw, false), result.toByteArray());
            }
        }
    }

    @Test void flacFixturesDecodeRealSamplesIncludingValuesBelowSixteenBitResolution() throws Exception {
        for (int channels : new int[]{1, 2}) for (int bits : new int[]{24, 32}) for (boolean floating : new boolean[]{false, true}) {
            try (InputStream input = getClass().getResourceAsStream("/audio/pcm" + bits + (channels == 1 ? "-mono.flac" : "-stereo.flac"));
                 AudioDecoder decoder = new FlacPcmDecoder(input, floating)) {
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                byte[] chunk;
                while ((chunk = decoder.readChunk(9)) != null) { assertTrue(chunk.length <= 9); result.write(chunk); }
                assertArrayEquals(new PcmOutput(channels, bits, 48000, floating).convert(samples(bits), false), result.toByteArray());
                if (floating) {
                    ByteBuffer decoded = ByteBuffer.wrap(result.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                    double scale = Math.scalb(1.0, bits - 1);
                    for (long sample : new long[]{0, 0, 1, -1, (1L << (bits - 1)) - 1, -(1L << (bits - 1)), 1L << (bits - 2), -(1L << (bits - 2))}) {
                        assertEquals((float)(sample / scale), decoded.getFloat());
                    }
                } else {
                    ByteBuffer decoded = ByteBuffer.wrap(result.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                    for (short expected : new short[]{0, 0, 0, -1, 32767, -32768, 16384, -16384}) assertEquals(expected, decoded.getShort());
                }
            }
        }
    }

    @Test void malformedWaveHeadersAndTruncatedPcmAreRejected() throws Exception {
        byte[] valid = wave(2, 24, samples(24), false);
        byte[] misaligned = valid.clone(); ByteBuffer.wrap(misaligned).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 23);
        assertThrows(IOException.class, () -> new WavPcmDecoder(new ByteArrayInputStream(misaligned), true));
        byte[] badRate = valid.clone(); ByteBuffer.wrap(badRate).order(ByteOrder.LITTLE_ENDIAN).putInt(28, 1);
        assertThrows(IOException.class, () -> new WavPcmDecoder(new ByteArrayInputStream(badRate), true));
        assertThrows(IOException.class, () -> new WavPcmDecoder(new ByteArrayInputStream(Arrays.copyOf(valid, 20)), true));
        try (var decoder = new WavPcmDecoder(new ByteArrayInputStream(Arrays.copyOf(valid, valid.length - 1)), true)) {
            assertThrows(UncheckedIOException.class, () -> decoder.readChunk(128));
        }
        try (var decoder = new WavPcmDecoder(new ByteArrayInputStream(valid), true)) {
            assertThrows(IllegalArgumentException.class, () -> decoder.readChunk(1));
        }
    }

    @Test void malformedFlacMetadataIsRejectedBeforeAllocation() {
        byte[] metadata = {'f','L','a','C', 0,127,127,127};
        assertThrows(IOException.class, () -> new FlacPcmDecoder(new ByteArrayInputStream(metadata), true));
    }

    private static byte[] samples(int bits) {
        long max = (1L << (bits - 1)) - 1, min = -(1L << (bits - 1));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (long value : new long[]{0, 0, 1, -1, max, min, 1L << (bits - 2), -(1L << (bits - 2))}) {
            for (int b = 0; b < bits / 8; b++) output.write((int) (value >> (b * 8)) & 255);
        }
        return output.toByteArray();
    }

    private static byte[] wave(int channels, int bits, byte[] raw, boolean junk) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + raw.length + (junk ? 10 : 0)).array());
        output.write("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        if (junk) output.write(new byte[]{'J','U','N','K',1,0,0,0,42,0});
        output.write("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(16).putShort((short)1)
                .putShort((short)channels).putInt(48000).putInt(48000 * channels * bits / 8)
                .putShort((short)(channels * bits / 8)).putShort((short)bits).array());
        output.write("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw.length).array());
        output.write(raw); return output.toByteArray();
    }
}
