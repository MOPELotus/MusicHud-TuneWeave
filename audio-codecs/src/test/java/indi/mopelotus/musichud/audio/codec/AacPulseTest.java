package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.net.*;
import java.nio.*;
import java.nio.file.*;
import net.sourceforge.jaad.aac.*;
import net.sourceforge.jaad.aac.syntax.*;
import org.junit.jupiter.api.Test;

class AacPulseTest {
  static AacTnsTest.Bits header(boolean shortWindow) {
    return new AacTnsTest.Bits().add(120, 8).add(0, 1).add(shortWindow ? 2 : 0, 2)
        .add(0, 1).add(0, shortWindow ? 4 : 6).add(0, shortWindow ? 7 : 1);
  }

  static void decodePulse(AacTnsTest.Bits bits) throws Exception {
    var config = Decoder.create(new byte[] {0x12, (byte) 0x88}).getConfig(); // LC 32k mono
    new ICStream(config).decode(bits.stream(), false, config);
  }

  @Test
  void pulsePositionsAreBoundedAndShortWindowSyntaxIsRejected() throws Exception {
    // At 32kHz, the last long-window SWB starts at 992. The last legal bin is 1023.
    var last = header(false).add(1, 1).add(0, 2).add(50, 6).add(31, 5).add(15, 4)
        .add(0, 1).add(0, 1);
    assertDoesNotThrow(() -> decodePulse(last));
    var beyond = header(false).add(1, 1).add(1, 2).add(50, 6).add(31, 5).add(0, 4)
        .add(1, 5).add(15, 4);
    assertTrue(assertThrows(AACException.class, () -> decodePulse(beyond)).getMessage().contains("1024"));
    var badBand = header(false).add(1, 1).add(0, 2).add(51, 6);
    assertThrows(AACException.class, () -> decodePulse(badBand));
    var shortPulse = header(true).add(1, 1);
    assertThrows(AACException.class, () -> decodePulse(shortPulse));
    var truncated = header(false).add(1, 1).add(3, 2).add(0, 6);
    assertThrows(RuntimeException.class, () -> decodePulse(truncated));
  }

  static float[] nonSpectralBand(int codebook, boolean pulse) throws Exception {
    var config = Decoder.create(new byte[] {0x12, 0x08}).getConfig();
    var bits = new AacTnsTest.Bits().add(160, 8).add(0, 1).add(0, 2).add(0, 1)
        .add(1, 6).add(0, 1).add(codebook, 4).add(1, 5);
    if (codebook == 13) bits.add(256, 9); // first noise energy delta0
    else if (codebook != 0) bits.add(0, 1); // intensity scalefactor delta0
    bits.add(pulse ? 1 : 0, 1);
    if (pulse) bits.add(0, 2).add(0, 6).add(1, 5).add(15, 4);
    bits.add(0, 1).add(0, 1);
    var stream = new ICStream(config);
    stream.decode(bits.stream(), false, config);
    return stream.getInvQuantData();
  }

  @Test
  void pulsesDoNotModifyZeroNoiseOrIntensityBands() throws Exception {
    for (int codebook : new int[] {0, 13, 14, 15}) {
      float[] expected = nonSpectralBand(codebook, false);
      assertArrayEquals(expected, nonSpectralBand(codebook, true));
      if (codebook == 13) {
        boolean hasNoise = false;
        for (float value : expected) hasNoise |= value != 0;
        assertTrue(hasNoise); // Compare real noise output, not two empty spectra.
      }
    }
  }

  @Test
  void actualPositiveNegativeZeroAndCumulativePulsesMatchIndependentPcmThroughR8() throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var isolated = new URLClassLoader(new URL[] {artifact.toUri().toURL()},
        ClassLoader.getPlatformClassLoader())) {
      for (String name : new String[] {"zero-positive", "positive-positive", "negative-negative",
          "repeat-same-bin", "cumulative-offsets", "zero-amplitude", "max-positive", "max-negative", "then-absent"}) {
        String mode = "aac-pulse/pulse-" + name;
        byte[] pcm = ShadedCodecTest.packets(getClass().getClassLoader(), mode);
        assertArrayEquals(pcm, ShadedCodecTest.packets(isolated, mode));
        var stereo = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        var reference = ByteBuffer.wrap(CodecFixtureTest.resource(mode + ".reference.s16le"))
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        assertEquals(8192, reference.remaining());
        assertEquals(16384, stereo.remaining());
        for (int frame = 0; frame < 8192; frame++) {
          assertEquals(stereo.get(frame * 2), stereo.get(frame * 2 + 1));
          if (name.equals("then-absent") && frame >= 5 * 1024)
            assertEquals(0, stereo.get(frame * 2), "Absent pulse must not reuse previous metadata");
          assertTrue(Math.abs(stereo.get(frame * 2) - reference.get(frame)) <= 1,
              name + " frame " + frame);
        }
      }
    }
  }
}
