package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.huffman.Huffman;
import org.junit.jupiter.api.Test;

class AacEscapeTest {
  static AacTnsTest.Bits escaped(AacTnsTest.Bits bits, int magnitude) {
    int width = 31 - Integer.numberOfLeadingZeros(magnitude);
    for (int i = 4; i < width; i++) bits.add(1, 1);
    return bits.add(0, 1).add(magnitude - (1 << width), width);
  }

  @Test
  void escapeMagnitudeIncludes8191AndPreservesSignsForEveryWidth() throws Exception {
    for (int width = 4; width <= 12; width++)
      for (int value : new int[] {1 << width, (1 << (width + 1)) - 1})
        for (boolean negative : new boolean[] {false, true}) {
          var bits = escaped(new AacTnsTest.Bits().add(450, 9).add(negative ? 1 : 0, 1), value);
          int[] data = new int[4];
          Huffman.decodeSpectralData(bits.stream(), 11, data, 0);
          assertEquals(negative ? -value : value, data[0]);
          assertEquals(0, data[1]);
        }
    var pair = new AacTnsTest.Bits().add(4, 5).add(1, 1).add(0, 1); // (16,16), -/+
    escaped(escaped(pair, 8191), 8190);
    int[] data = new int[4];
    Huffman.decodeSpectralData(pair.stream(), 11, data, 0);
    assertArrayEquals(new int[] {-8191, 8190, 0, 0}, data);
  }

  @Test
  void oversizedAndTruncatedEscapesCannotWrapOrReadUnboundedPrefixes() throws Exception {
    for (int ones : new int[] {9, 32, 128}) {
      var bits = new AacTnsTest.Bits().add(450, 9).add(0, 1);
      for (int i = 0; i < ones; i++) bits.add(1, 1);
      var input = bits.add(0, 1).stream();
      assertThrows(AACException.class, () -> Huffman.decodeSpectralData(input, 11, new int[4], 0));
      assertEquals(19, input.getPosition()); // Reject the ninth unary one before any shift/tail read.
    }
    var truncated = new AacTnsTest.Bits().add(450, 9).add(0, 1);
    for (int i = 0; i < 8; i++) truncated.add(1, 1);
    var input = truncated.add(0, 1).stream();
    assertThrows(RuntimeException.class, () -> Huffman.decodeSpectralData(input, 11, new int[4], 0));
  }

  @Test
  void realBoundaryFramesMatchIndependentPcmAndSurviveR8() throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var isolated = new URLClassLoader(new URL[] {artifact.toUri().toURL()},
        ClassLoader.getPlatformClassLoader())) {
      for (int value : new int[] {8190, 8191})
        for (String sign : new String[] {"positive", "negative"}) {
          String mode = "aac-escape/escape-" + value + "-" + sign;
          byte[] pcm = ShadedCodecTest.packets(getClass().getClassLoader(), mode);
          assertArrayEquals(pcm, ShadedCodecTest.packets(isolated, mode));
          var stereo = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
          var reference = ByteBuffer.wrap(CodecFixtureTest.resource(mode + ".reference.s16le"))
              .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
          assertEquals(8192, reference.remaining());
          assertEquals(16384, stereo.remaining());
          for (int frame = 0; frame < 8192; frame++) {
            assertEquals(stereo.get(frame * 2), stereo.get(frame * 2 + 1));
            assertEquals(reference.get(frame), stereo.get(frame * 2), "frame " + frame);
          }
        }
    }
  }
}
