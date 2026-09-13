package indi.mopelotus.musichud.client.audio.decoder;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MultichannelPcmTest {
    @Test void nativeMultichannelCapabilityPreservesEachFlacSpeakerAndOutputTiming() throws Exception {
        for (int channels : new int[]{6, 8}) for (boolean floating : new boolean[]{false, true}) {
            try (var input = getClass().getResourceAsStream("/audio/pcm24-surround-" + channels + ".flac");
                 var decoder = new FlacPcmDecoder(input, floating, true)) {
                int frameSize = channels * (floating ? 4 : 2);
                assertEquals(frameSize, decoder.getFrameSize());
                assertEquals(channels, indi.mopelotus.musichud.client.audio.OpenAlFormatSelector.channels(decoder.getFormat()));
                ByteArrayOutputStream all = new ByteArrayOutputStream(); byte[] chunk;
                while ((chunk = decoder.readChunk(97)) != null) { assertEquals(0, chunk.length % frameSize); all.write(chunk); }
                assertEquals((channels + 480) * frameSize, all.size());
                ByteBuffer samples = ByteBuffer.wrap(all.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
                for (int frame = 0; frame < channels; frame++) for (int channel = 0; channel < channels; channel++) {
                    if (floating) assertEquals(frame == channel ? .5f : 0f, samples.getFloat());
                    else assertEquals(frame == channel ? 16384 : 0, samples.getShort());
                }
                assertEquals(10, decoder.getPositionMillis());
            }
            var wav = new WavPcmDecoder(new ByteArrayInputStream(wave(channels, PcmChannelLayout.flacDefault(channels), new byte[channels * 3])), floating, true);
            try (wav) { assertEquals(channels * (floating ? 4 : 2), wav.readChunk(64).length); }
        }
    }
    @Test void everyStandardSpeakerRoutesToTheCorrectSideWithoutClipping() throws Exception {
        for (int channels = 3; channels <= 8; channels++) {
            long mask = PcmChannelLayout.flacDefault(channels);
            var output = new PcmOutput(channels, 24, 48000, true, mask);
            int channel = 0;
            for (int bit = 0; bit < 18; bit++) if ((mask & 1L << bit) != 0) {
                byte[] raw = new byte[channels * 3]; raw[channel * 3 + 2] = 64;
                var values = ByteBuffer.wrap(output.convert(raw, false)).order(ByteOrder.LITTLE_ENDIAN);
                float left = values.getFloat(), right = values.getFloat();
                switch (bit) {
                    case 0, 4, 9 -> { assertTrue(left > 0); assertEquals(0, right); }
                    case 1, 5, 10 -> { assertEquals(0, left); assertTrue(right > 0); }
                    default -> { assertTrue(left > 0); assertEquals(left, right); }
                }
                channel++;
            }
            byte[] full = new byte[channels * 3]; Arrays.fill(full, (byte)255);
            for (int c = 0; c < channels; c++) full[c * 3 + 2] = 127;
            var mixed = ByteBuffer.wrap(output.convert(full, false)).order(ByteOrder.LITTLE_ENDIAN);
            for (int side = 0; side < 2; side++) assertEquals(1, mixed.getFloat(), 0.000001);
        }
    }

    @Test void waveExtensibleAndRealFlacAgreeOnImpulseRoutingAndFrameTiming() throws Exception {
        for (int channels : new int[]{6, 8}) for (boolean floating : new boolean[]{false, true}) {
            byte[] raw = new byte[(channels + 480) * channels * 3];
            for (int c = 0; c < channels; c++) raw[(c * channels + c) * 3 + 2] = 64;
            try (var wav = new WavPcmDecoder(new ByteArrayInputStream(wave(channels, PcmChannelLayout.flacDefault(channels), raw)), floating);
                 var flac = new FlacPcmDecoder(getClass().getResourceAsStream("/audio/pcm24-surround-" + channels + ".flac"), floating)) {
                byte[] result = drain(wav);
                assertArrayEquals(result, drain(flac));
                assertEquals((channels + 480) * (floating ? 8 : 4), result.length);
                assertEquals(10, wav.getPositionMillis()); assertEquals(10, flac.getPositionMillis());
                double root = Math.sqrt(.5), gain = 1 + root + .5 + root * (channels == 8 ? 2 : 1);
                ByteBuffer values = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
                double[] left = channels == 6 ? new double[]{1, 0, root, .5, root, 0} : new double[]{1, 0, root, .5, root, 0, root, 0};
                double[] right = channels == 6 ? new double[]{0, 1, root, .5, 0, root} : new double[]{0, 1, root, .5, 0, root, 0, root};
                for (int c = 0; c < channels; c++) for (double weight : new double[]{left[c], right[c]}) {
                    if (floating) assertEquals(.5 * weight / gain, values.getFloat(), 0.000001);
                    else assertEquals(Math.round(16384 * weight / gain), values.getShort());
                }
            }
        }
    }

    @Test void explicitFlacMaskOverridesDefaultAndSkipsOnlyUnassignedChannels() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/audio/pcm24-surround-6.flac")) { fixture = input.readAllBytes(); }
        try (var decoder = new FlacPcmDecoder(new ByteArrayInputStream(withComments(fixture, comments("waveformatextensible_channel_mask=0X3"))), true)) {
            ByteBuffer samples = ByteBuffer.wrap(drain(decoder)).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(.5f, samples.getFloat()); assertEquals(0, samples.getFloat());
            assertEquals(0, samples.getFloat()); assertEquals(.5f, samples.getFloat());
            while (samples.hasRemaining()) assertEquals(0, samples.getFloat());
        }
        assertEquals(3L, PcmChannelLayout.vorbisMask(comments("WAVEFORMATEXTENSIBLE_CHANNEL_MASK=0x0000000000003")));
        for (String mask : new String[]{"0x0", "0x40000", "0x63f", "-1", "0x", "0xGG", "0x100000000"}) {
            byte[] invalid = withComments(fixture, comments("WAVEFORMATEXTENSIBLE_CHANNEL_MASK=" + mask));
            assertThrows(IOException.class, () -> new FlacPcmDecoder(new ByteArrayInputStream(invalid), true), mask);
        }
    }

    @Test void malformedAndAmbiguousLayoutsDoNotSilentlyFallBack() throws Exception {
        for (long mask : new long[]{0, 3, 0x63f, 0x4003f}) {
            byte[] invalid = wave(6, mask, new byte[18]);
            assertThrows(IOException.class, () -> new WavPcmDecoder(new ByteArrayInputStream(invalid), true));
        }
        byte[] valid = comments("WAVEFORMATEXTENSIBLE_CHANNEL_MASK=0x3");
        for (int size = 0; size < valid.length; size++) {
            byte[] truncated = Arrays.copyOf(valid, size);
            assertThrows(IOException.class, () -> PcmChannelLayout.vorbisMask(truncated));
        }
        assertThrows(IOException.class, () -> PcmChannelLayout.vorbisMask(comments("WAVEFORMATEXTENSIBLE_CHANNEL_MASK=0x3", "WAVEFORMATEXTENSIBLE_CHANNEL_MASK=0x3")));
        assertThrows(IOException.class, () -> PcmChannelLayout.vorbisMask(new byte[]{-1,-1,-1,127}));
        assertThrows(IllegalArgumentException.class, () -> new PcmOutput(6, 24, 48000, true));
    }

    @Test void allNativeDepthsRetainHeadroomAndFloatInputCannotPoisonTheMix() {
        for (int bits : new int[]{16, 24, 32}) for (boolean floating : new boolean[]{false, true}) {
            var output = new PcmOutput(6, bits, 48000, floating, 0x3f);
            byte[] raw = new byte[6 * bits / 8];
            for (int c = 0; c < 6; c++) raw[(c + 1) * bits / 8 - 1] = (byte)128;
            var values = ByteBuffer.wrap(output.convert(raw, false)).order(ByteOrder.LITTLE_ENDIAN);
            for (int side = 0; side < 2; side++) {
                if (floating && bits > 16) assertEquals(-1f, values.getFloat());
                else assertEquals(Short.MIN_VALUE, values.getShort());
            }
        }
        var output = new PcmOutput(6, 32, 48000, true, 0x3f);
        ByteBuffer input = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0, 0, 0}) input.putFloat(value);
        assertArrayEquals(new byte[8], output.convert(input.array(), true));
        assertThrows(IllegalArgumentException.class, () -> output.convert(new byte[23], false));
    }

    private static byte[] drain(AudioDecoder decoder) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] chunk;
        while ((chunk = decoder.readChunk(17)) != null) {
            assertTrue(chunk.length <= 17); assertEquals(0, chunk.length % decoder.getFrameSize()); result.write(chunk);
        }
        return result.toByteArray();
    }
    private static byte[] wave(int channels, long mask, byte[] raw) {
        ByteBuffer bytes = ByteBuffer.allocate(68 + raw.length).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(bytes.capacity() - 8).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        bytes.putInt(40).putShort((short)65534).putShort((short)channels).putInt(48000).putInt(48000 * channels * 3);
        bytes.putShort((short)(channels * 3)).putShort((short)24).putShort((short)22).putShort((short)24).putInt((int)mask);
        bytes.putInt(1).put(new byte[]{0,0,16,0,(byte)128,0,0,(byte)170,0,56,(byte)155,113});
        bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(raw.length).put(raw);
        return bytes.array();
    }
    private static byte[] comments(String... fields) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(new byte[4]);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(fields.length).array());
        for (String field : fields) {
            byte[] encoded = field.getBytes(StandardCharsets.UTF_8);
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(encoded.length).array()); out.write(encoded);
        }
        return out.toByteArray();
    }
    private static byte[] withComments(byte[] flac, byte[] comments) throws IOException {
        int offset = 4; boolean last;
        do {
            last = (flac[offset] & 128) != 0;
            int size = (flac[offset + 1] & 255) << 16 | (flac[offset + 2] & 255) << 8 | flac[offset + 3] & 255;
            offset += size + 4;
        } while (!last);
        byte[] prefix = Arrays.copyOf(flac, 42); prefix[4] = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(prefix);
        out.write(new byte[]{(byte)132, (byte)(comments.length >> 16), (byte)(comments.length >> 8), (byte)comments.length});
        out.write(comments); out.write(flac, offset, flac.length - offset); return out.toByteArray();
    }
}
