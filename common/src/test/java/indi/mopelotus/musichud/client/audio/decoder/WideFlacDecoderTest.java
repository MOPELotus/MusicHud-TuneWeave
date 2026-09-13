package indi.mopelotus.musichud.client.audio.decoder;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WideFlacDecoderTest {
    @Test void declaredAudioEndDoesNotDecodeTrailingTagsOrDiscardFinalPcm() throws Exception {
        byte[] valid = resource("pcm32-wide-8-verbatim.flac");
        ByteArrayOutputStream tagged = new ByteArrayOutputStream();
        tagged.write(valid);
        tagged.write("TAG trailing provider metadata".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (int chunkSize : new int[]{8, 13, 4096}) {
            try (var decoder = new FlacPcmDecoder(new ByteArrayInputStream(tagged.toByteArray()), true)) {
                ByteArrayOutputStream pcm = new ByteArrayOutputStream();
                byte[] chunk;
                while ((chunk = decoder.readChunk(chunkSize)) != null) pcm.write(chunk);
                assertArrayEquals(new PcmOutput(2, 32, 48000, true).convert(resource("pcm32-wide-verbatim.s32le"), false), pcm.toByteArray());
                assertNull(decoder.readChunk(chunkSize));
            }
        }
    }

    @Test void allStereoDecorrelationsPreserveFullDepthForVerbatimFixedAndLpc() throws Exception {
        for (String coding : List.of("verbatim", "fixed", "lpc")) for (int assignment : new int[]{8, 9, 10})
            for (boolean floating : new boolean[]{false, true}) {
                byte[] raw = resource("pcm32-wide-" + coding + ".s32le");
                try (var decoder = new FlacPcmDecoder(new ByteArrayInputStream(resource("pcm32-wide-" + assignment + "-" + coding + ".flac")), floating)) {
                    ByteArrayOutputStream result = new ByteArrayOutputStream(); byte[] chunk;
                    while ((chunk = decoder.readChunk(13)) != null) {
                        assertTrue(chunk.length <= 13); assertEquals(0, chunk.length % decoder.getFrameSize()); result.write(chunk);
                    }
                    assertEquals(48000, decoder.getSampleRate());
                    assertArrayEquals(new PcmOutput(2, 32, 48000, floating).convert(raw, false), result.toByteArray(), coding + " / " + assignment);
                }
            }
    }

    @Test void badCrcTruncatedDataAndInvalidSequenceAreRejected() throws Exception {
        byte[] valid = resource("pcm32-wide-8-verbatim.flac");
        for (int position : new int[]{49, valid.length - 1}) {
            byte[] broken = valid.clone(); broken[position] ^= 1;
            try (var decoder = new FlacPcmDecoder(new ByteArrayInputStream(broken), true)) {
                assertThrows(UncheckedIOException.class, () -> decoder.readChunk(1024));
            }
        }
        byte[] wrongSequence = valid.clone(); wrongSequence[46] = 1;
        wrongSequence[49] = (byte) crc(wrongSequence, 42, 49, 8, 7);
        int frameCrc = crc(wrongSequence, 42, wrongSequence.length - 2, 16, 0x8005);
        wrongSequence[wrongSequence.length - 2] = (byte)(frameCrc >>> 8); wrongSequence[wrongSequence.length - 1] = (byte)frameCrc;
        for (byte[] broken : List.of(wrongSequence, Arrays.copyOf(valid, valid.length - 1)))
            try (var decoder = new FlacPcmDecoder(new ByteArrayInputStream(broken), true)) {
                assertThrows(UncheckedIOException.class, () -> decoder.readChunk(1024));
            }
        ByteArrayOutputStream overlong = new ByteArrayOutputStream();
        overlong.write(valid, 0, 46); overlong.write(new byte[]{(byte)192, (byte)128}); overlong.write(valid, 47, valid.length - 47);
        byte[] badNumber = overlong.toByteArray();
        badNumber[50] = (byte)crc(badNumber, 42, 50, 8, 7);
        int checksum = crc(badNumber, 42, badNumber.length - 2, 16, 0x8005);
        badNumber[badNumber.length - 2] = (byte)(checksum >>> 8); badNumber[badNumber.length - 1] = (byte)checksum;
        try (var decoder = new FlacPcmDecoder(new ByteArrayInputStream(badNumber), true)) {
            assertThrows(UncheckedIOException.class, () -> decoder.readChunk(1024));
        }
    }

    @Test void maliciousUnboundedFrameInputAndInterruptedInputStop() throws Exception {
        var endless = new InputStream() {
            public int read() { return 0; }
            public int read(byte[] buffer, int offset, int length) { Arrays.fill(buffer, offset, offset + length, (byte)0); return length; }
        };
        try (var input = new StreamingFlacInput(endless)) {
            assertThrows(IOException.class, () -> {
                byte[] buffer = new byte[4096];
                for (int i = 0; i < 5000; i++) input.readFully(buffer);
            });
            assertTrue(input.getPosition() < 17 * 1024 * 1024);
        }
        try (var input = new StreamingFlacInput(new ByteArrayInputStream(new byte[16]))) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedIOException.class, input::readByte); }
            finally { Thread.interrupted(); }
        }
    }

    private static byte[] resource(String name) throws IOException {
        try (var input = WideFlacDecoderTest.class.getResourceAsStream("/audio/" + name)) { return Objects.requireNonNull(input).readAllBytes(); }
    }
    private static int crc(byte[] bytes, int start, int end, int width, int poly) {
        int result = 0;
        for (int i = start; i < end; i++) {
            result ^= (bytes[i] & 255) << (width - 8);
            for (int bit = 0; bit < 8; bit++) result = ((result << 1) ^ ((result & (1 << (width - 1))) == 0 ? 0 : poly)) & ((1 << width) - 1);
        }
        return result;
    }
}
