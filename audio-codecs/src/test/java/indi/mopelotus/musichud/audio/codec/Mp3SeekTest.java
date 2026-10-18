package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class Mp3SeekTest {
  @Test
  void boundedPrerollMatchesSequentialAudiblePcmAndGlobalPosition() throws Exception {
    byte[] encoded = CodecFixtureTest.resource("mp3.mp3");
    var input = new TrackedInput(encoded);
    var index = Mp3SeekIndex.build(input, () -> false);
    assertEquals(1, input.closes);
    assertEquals(48000, index.sampleRate());
    assertEquals(2, index.channels());
    assertEquals(4800, index.audibleFrames());
    assertFalse(index.points().isEmpty());
    assertThrows(UnsupportedOperationException.class, () -> index.points().clear());
    byte[] sequential;
    try (var decoder = JavaCodecs.mp3(new ByteArrayInputStream(encoded))) {
      sequential = read(decoder, 0);
    }
    for (long sample : new long[] {0, 1, 575, 1105, 2399, 4799, 4800}) {
      var plan = index.plan(sample);
      var stream =
          new TrackedInput(Arrays.copyOfRange(encoded, (int) plan.byteOffset(), encoded.length));
      try (var decoder = JavaCodecs.mp3Indexed(stream, index, plan)) {
        assertEquals(sample, decoder.framePosition());
        assertEquals(index.sampleRate(), decoder.format().sampleRate());
        assertArrayEquals(
            Arrays.copyOfRange(sequential, (int) sample * 4, sequential.length),
            read(decoder, sample));
        assertEquals(4800, decoder.framePosition());
      }
      assertEquals(1, stream.closes);
    }
    assertThrows(IllegalArgumentException.class, () -> index.plan(-1));
    assertThrows(IllegalArgumentException.class, () -> index.plan(4801));
    var invalid = new Mp3SeekIndex.Plan(0, 0, 0, 1, 0);
    var stream = new TrackedInput(encoded);
    assertThrows(IOException.class, () -> JavaCodecs.mp3Indexed(stream, index, invalid));
    assertEquals(1, stream.closes);
  }

  @Test
  void malformedAndCancelledIndexAlwaysClosesOwnedInput() throws Exception {
    var cancelled = new TrackedInput(CodecFixtureTest.resource("mp3.mp3"));
    assertThrows(InterruptedIOException.class, () -> Mp3SeekIndex.build(cancelled, () -> true));
    assertEquals(1, cancelled.closes);
    var broken = new TrackedInput(new byte[] {1, 2, 3, 4});
    assertThrows(IOException.class, () -> Mp3SeekIndex.build(broken, () -> false));
    assertEquals(1, broken.closes);
    var noProgress =
        new InputStream() {
          int closes;

          public int read() {
            return 0;
          }

          public int read(byte[] b, int o, int n) {
            return n == 0 ? 0 : 0;
          }

          public void close() {
            closes++;
          }
        };
    assertThrows(IOException.class, () -> Mp3SeekIndex.build(noProgress, () -> false));
    assertEquals(1, noProgress.closes);
  }

  private static byte[] read(PcmDecoder decoder, long start) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] b = new byte[113 * decoder.format().frameBytes()];
    long position = start;
    int n;
    while ((n = decoder.readFrames(b, 0, 113)) != -1) {
      assertTrue(n > 0);
      position += n;
      assertEquals(position, decoder.framePosition());
      out.write(b, 0, n * decoder.format().frameBytes());
    }
    return out.toByteArray();
  }

  static final class TrackedInput extends ByteArrayInputStream {
    int closes;

    TrackedInput(byte[] b) {
      super(b);
    }

    public void close() {
      closes++;
    }
  }
}
