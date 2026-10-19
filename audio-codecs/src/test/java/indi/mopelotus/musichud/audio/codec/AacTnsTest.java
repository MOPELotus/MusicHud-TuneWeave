package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.nio.*;
import java.nio.file.*;
import java.net.*;
import java.security.MessageDigest;
import net.sourceforge.jaad.aac.*;
import net.sourceforge.jaad.aac.syntax.*;
import net.sourceforge.jaad.aac.tools.TNS;
import org.junit.jupiter.api.Test;

class AacTnsTest {
  // Construct actual syntax, rather than setting the filter's private parsed state.
  static class Bits {
    StringBuilder value = new StringBuilder();
    Bits add(int value, int count) {
      for (int i = count - 1; i >= 0; i--) this.value.append((value >>> i) & 1);
      return this;
    }
    BitStream stream() {
      byte[] data = new byte[(value.length() + 7) / 8];
      for (int i = 0; i < value.length(); i++)
        data[i / 8] |= (byte) ((value.charAt(i) - '0') << (7 - i % 8));
      return BitStream.open(data);
    }
  }

  static ICStream stream(boolean shortWindow, int maxSfb) throws Exception {
    var config = Decoder.create(new byte[] {0x12, 0x10}).getConfig(); // LC 44.1 kHz stereo
    var stream = new ICStream(config);
    var bits = new Bits().add(0, 1).add(shortWindow ? 2 : 0, 2).add(0, 1);
    bits.add(maxSfb, shortWindow ? 4 : 6).add(shortWindow ? 127 : 0, shortWindow ? 7 : 1);
    stream.getInfo().decode(bits.stream(), false);
    return stream;
  }

  static Bits filter(Bits bits, boolean shortWindow, int length, int order,
      boolean reverse, int compression, int resolution, int coefficient) {
    bits.add(length, shortWindow ? 4 : 6).add(order, shortWindow ? 3 : 5);
    if (order > 0) {
      bits.add(reverse ? 1 : 0, 1).add(compression, 1);
      for (int i = 0; i < order; i++) bits.add(coefficient, resolution + 3 - compression);
    }
    return bits;
  }

  @Test
  void firstOrderImpulseHasAnalyticResponseForBothDirectionsAndAllCoefficientWidths() throws Exception {
    for (boolean reverse : new boolean[] {false, true})
      for (int compression = 0; compression <= 1; compression++)
        for (int resolution = 0; resolution <= 1; resolution++) {
          var stream = stream(false, 2);
          var bits = filter(new Bits().add(1, 2).add(resolution, 1), false, 63, 1,
              reverse, compression, resolution, 1);
          var tns = new TNS();
          tns.decode(bits.stream(), stream.getInfo(), Profile.AAC_LC);
          int end = stream.getInfo().getSWBOffsets()[2];
          float[] data = new float[1024];
          data[reverse ? end - 1 : 0] = 1;
          tns.process(stream, data, SampleFrequency.SF_44100, false);
          double reflection = resolution == 0 ? -0.43388373 : -0.20791170;
          for (int i = 0; i < end; i++)
            assertEquals(Math.pow(reflection, i), data[reverse ? end - 1 - i : i], 1e-6);
          for (int i = end; i < data.length; i++) assertEquals(0, data[i]);
          // LTP uses the inverse (moving-average) tool and retains the original input history.
          tns.process(stream, data, SampleFrequency.SF_44100, true);
          for (int i = 0; i < data.length; i++)
            assertEquals(i == (reverse ? end - 1 : 0) ? 1 : 0, data[i], 1e-6);
        }
  }

  @Test
  void groupedShortWindowsHaveIndependentFiltersAndNoHistoryAcrossFrames() throws Exception {
    var stream = stream(true, 2);
    var bits = new Bits();
    for (int w = 0; w < 8; w++)
      filter(bits.add(1, 1).add(0, 1), true, 15, 1, w % 2 != 0, 0, 0, 1);
    var tns = new TNS();
    tns.decode(bits.stream(), stream.getInfo(), Profile.AAC_LC);
    float[] input = new float[1024];
    int end = stream.getInfo().getSWBOffsets()[2];
    for (int w = 0; w < 8; w++) input[w * 128 + (w % 2 == 0 ? 0 : end - 1)] = 1;
    float[] data = input.clone();
    tns.process(stream, data, SampleFrequency.SF_44100, false);
    for (int w = 0; w < 8; w++) {
      for (int i = 0; i < end; i++)
        assertEquals(Math.pow(-0.43388373, i),
            data[w * 128 + (w % 2 == 0 ? i : end - 1 - i)], 1e-6);
      for (int i = end; i < 128; i++) assertEquals(0, data[w * 128 + i]);
    }
    var empty = new Bits();
    for (int w = 0; w < 8; w++) empty.add(0, 1);
    tns.decode(empty.stream(), stream.getInfo(), Profile.AAC_LC);
    var unchanged = input.clone();
    tns.process(stream, unchanged, SampleFrequency.SF_44100, false);
    assertArrayEquals(input, unchanged);
  }

  @Test
  void zeroOrderStillAdvancesBandsAndAdjacentFiltersResetHistory() throws Exception {
    var stream = stream(false, 49);
    var info = stream.getInfo();
    int max = SampleFrequency.SF_44100.getMaximalTNS_SFB(false);
    var bits = new Bits().add(3, 2).add(0, 1);
    filter(bits, false, info.getSWBCount() - max, 0, false, 0, 0, 0);
    filter(bits, false, 1, 1, false, 0, 0, 1);
    filter(bits, false, 1, 1, true, 0, 0, 1);
    var tns = new TNS();
    tns.decode(bits.stream(), info, Profile.AAC_LC);
    float[] data = new float[1024];
    int[] offsets = info.getSWBOffsets();
    data[offsets[max - 1]] = 1;
    data[offsets[max - 1] - 1] = 1;
    data[offsets[max]] = 7;
    tns.process(stream, data, SampleFrequency.SF_44100, false);
    assertEquals(1, data[offsets[max - 1]]);
    assertEquals(1, data[offsets[max - 1] - 1]);
    assertEquals(-0.43388373f, data[offsets[max - 1] + 1]);
    assertEquals(-0.43388373f, data[offsets[max - 1] - 2]);
    assertEquals(7, data[offsets[max]]);
  }

  @Test
  void profileOrderLimitsTruncationAndEmptyBandsAreBounded() throws Exception {
    var stream = stream(false, 2);
    var bad = new Bits().add(1, 2).add(0, 1).add(63, 6).add(13, 5);
    assertThrows(AACException.class, () -> new TNS().decode(bad.stream(), stream.getInfo(), Profile.AAC_LC));
    var tooLong = new Bits().add(1, 2).add(0, 1).add(63, 6).add(21, 5);
    assertThrows(AACException.class, () -> new TNS().decode(tooLong.stream(), stream.getInfo(), Profile.AAC_MAIN));
    var truncated = new Bits().add(1, 2).add(1, 1).add(63, 6).add(12, 5).add(0, 2);
    assertThrows(RuntimeException.class, () -> new TNS().decode(truncated.stream(), stream.getInfo(), Profile.AAC_LC));
    for (int order : new int[] {12, 20}) {
      var bits = filter(new Bits().add(1, 2).add(0, 1), false, 63, order, false, 0, 0, 0);
      var tns = new TNS();
      tns.decode(bits.stream(), stream.getInfo(), order == 20 ? Profile.AAC_MAIN : Profile.AAC_LC);
      var data = new float[1024];
      Arrays.fill(data, 0.25f);
      tns.process(stream, data, SampleFrequency.SF_44100, false);
      for (float value : data) assertEquals(0.25f, value);
    }
    var empty = stream(false, 0);
    var tns = new TNS();
    tns.decode(filter(new Bits().add(1, 2).add(0, 1), false, 63, 1, false, 0, 0, 1).stream(), empty.getInfo(), Profile.AAC_LC);
    float[] data = new float[1024];
    Arrays.fill(data, 1);
    tns.process(empty, data, SampleFrequency.SF_44100, false);
    for (float value : data) assertEquals(1, value);
  }
  @Test
  void distinctReflectionCoefficientsHaveAnalyticSecondAndThirdOrderResponses() throws Exception {
    var stream = stream(false, 2);
    for (int order : new int[] {2, 3}) {
      var bits = new Bits().add(1, 2).add(0, 1).add(63, 6).add(order, 5).add(0, 2);
      for (int i = 0; i < order; i++) bits.add(i + 1, 3);
      var tns = new TNS();
      tns.decode(bits.stream(), stream.getInfo(), Profile.AAC_LC);
      var data = new float[1024];
      data[0] = 1;
      tns.process(stream, data, SampleFrequency.SF_44100, false);
      double r0 = 0.43388373, r1 = 0.78183150, r2 = 0.97492790;
      double a = r0 * (1 + r1), b = r1, c = 0;
      if (order == 3) {
        a += r2 * r1;
        b += r2 * r0 * (1 + r1);
        c = r2;
      }
      assertEquals(-a, data[1], 1e-6);
      assertEquals(a * a - b, data[2], 1e-6);
      assertEquals(-a * a * a + 2 * a * b - c, data[3], 1e-6);
    }
  }

  @Test
  void highOrderSparsePolesStayFiniteAndMalformedSpectrumIsRejected() throws Exception {
    var stream = stream(false, 42);
    for (int order : new int[] {12, 20}) {
      var bits = new Bits().add(1, 2).add(0, 1).add(63, 6).add(order, 5).add(0, 2);
      for (int i = 0; i < order; i++) bits.add(i == order - 1 ? 3 : 0, 3);
      var tns = new TNS();
      tns.decode(bits.stream(), stream.getInfo(), order == 20 ? Profile.AAC_MAIN : Profile.AAC_LC);
      float[] data = new float[1024];
      data[0] = 1;
      tns.process(stream, data, SampleFrequency.SF_44100, false);
      // A(z)=1+r*z^-order has an analytic comb impulse response.
      int end = stream.getInfo().getSWBOffsets()[42];
      for (int i = 0; i < end; i++) {
        assertTrue(Float.isFinite(data[i]));
        assertEquals(i % order == 0 ? Math.pow(-0.97492790, i / order) : 0, data[i], 1e-6);
      }
      data[0] = Float.POSITIVE_INFINITY;
      assertThrows(IllegalArgumentException.class,
          () -> tns.process(stream, data, SampleFrequency.SF_44100, false));
    }
    var invalid = stream(false, 63);
    assertThrows(AACException.class,
        () -> new TNS().decode(new Bits().add(0, 2).stream(), invalid.getInfo(), Profile.AAC_LC));
  }

  @Test
  void realEncodedLongAndShortFramesMatchIndependentOracleThroughR8() throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var isolated = new URLClassLoader(new URL[] {artifact.toUri().toURL()},
        ClassLoader.getPlatformClassLoader())) {
      for (int enabled = 0; enabled <= 1; enabled++) {
        String mode = "aac-tns/chirp-44100-tns" + enabled;
        byte[] pcm = ShadedCodecTest.packets(getClass().getClassLoader(), mode);
        assertArrayEquals(pcm, ShadedCodecTest.packets(isolated, mode));
        var stereo = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        assertEquals(36864 * 2, stereo.remaining());
        var mono = ByteBuffer.allocate(36864 * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int frame = 0; frame < 36864; frame++) {
          short left = stereo.get(frame * 2);
          assertEquals(left, stereo.get(frame * 2 + 1));
          mono.putShort(left);
        }
        if (enabled == 0) {
          assertEquals("3c6bb716f4b3fe060f138dac88ed50a77d987ecb65a4c716f4d0f14a9c7a95b1",
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mono.array())));
        } else {
          var reference = ByteBuffer.wrap(CodecFixtureTest.resource(mode + ".reference.s16le"))
              .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
          assertEquals(36864, reference.remaining());
          for (int frame = 0; frame < 36864; frame++)
            assertTrue(Math.abs(stereo.get(frame * 2) - reference.get(frame)) <= 1,
                "Independent PCM oracle mismatch at frame " + frame);
        }
      }
    }
  }

}
