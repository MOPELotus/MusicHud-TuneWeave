package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.syntax.BitStream;
import net.sourceforge.jaad.aac.syntax.ICSInfo;
import org.junit.jupiter.api.Test;

class AacMainPredictionTest {
  private static ICSInfo info() {
    return new ICSInfo(Decoder.create(new byte[] {0x0a, 0x10}).getConfig()); // Main 44.1k stereo
  }

  private static AacTnsTest.Bits longSyntax(int maxSfb, boolean present, int resetGroup) {
    var bits = new AacTnsTest.Bits().add(0, 1).add(0, 2).add(0, 1)
        .add(maxSfb, 6).add(present ? 1 : 0, 1);
    if (present) {
      bits.add(resetGroup < 0 ? 0 : 1, 1);
      if (resetGroup >= 0) bits.add(resetGroup, 5);
      for (int band = 0; band < Math.min(40, maxSfb); band++) bits.add(1, 1);
    }
    return bits;
  }

  private static float[] signal(int frame, boolean right) {
    float[] data = new float[1024];
    for (int i = 0; i < 96; i++)
      data[i] = (float) ((right ? -.7 : .4) * (frame + 1) + .1 * Math.sin(i * .21));
    return data;
  }

  @Test
  void commonWindowCopiesSyntaxWhilePreservingIndependentChannelHistories() {
    var left = info(); var right = info(); var independent = info();
    for (int frame = 0; frame < 9; frame++) {
      boolean present = frame % 3 != 1;
      left.decode(longSyntax(20, present, -1).stream(), true);
      right.setCommonData(BitStream.open(new byte[0]), left);
      independent.decode(longSyntax(20, present, -1).stream(), false);
      float[] l = signal(frame, false), r = signal(frame, true), expected = r.clone();
      left.processICP(l); right.processICP(r); independent.processICP(expected);
      assertArrayEquals(expected, r, 0, "Common syntax must not replace or share channel state");
      if (frame > 1 && present) assertFalse(Arrays.equals(signal(frame, true), r));
    }
  }

  @Test
  void unusedBandsStillMaintainHistoryWhileMaxSfbShrinks() {
    var narrow = info(); var full = info();
    for (int frame = 0; frame < 6; frame++) {
      narrow.decode(longSyntax(frame < 3 ? 20 : 1, frame < 3, -1).stream(), false);
      full.decode(longSyntax(20, frame < 3, -1).stream(), false);
      float[] a = frame < 3 ? signal(frame, false) : new float[1024];
      float[] b = a.clone(); narrow.processICP(a); full.processICP(b);
    }
    narrow.decode(longSyntax(20, true, -1).stream(), false);
    full.decode(longSyntax(20, true, -1).stream(), false);
    float[] a = new float[1024], b = new float[1024];
    narrow.processICP(a); full.processICP(b);
    assertArrayEquals(b, a, 0);
    assertEquals(0, a[20], "Zero spectral input in unused bands must advance past old history");
  }

  @Test
  void framesWithoutPredictionSyntaxStillSeedLaterPredictions() {
    var info = info();
    for (int frame = 0; frame < 4; frame++) {
      info.decode(longSyntax(20, false, -1).stream(), false);
      info.processICP(signal(frame, false));
    }
    info.decode(longSyntax(20, true, -1).stream(), false);
    float[] data = new float[1024];
    info.processICP(data);
    assertTrue(Math.abs(data[20]) > 0, "Unsignaled frames must update later-used predictor state");
  }

  @Test
  void shortWindowResetsHistoryWithoutPredictionSyntax() {
    var previous = info(); var fresh = info();
    for (int frame = 0; frame < 4; frame++) {
      previous.decode(longSyntax(20, true, -1).stream(), false);
      previous.processICP(signal(frame, false));
    }
    var shortBits = new AacTnsTest.Bits().add(0, 1).add(2, 2).add(0, 1)
        .add(0, 4).add(127, 7);
    previous.decode(shortBits.stream(), false);
    previous.processICP(new float[1024]);
    previous.decode(longSyntax(20, true, -1).stream(), false);
    fresh.decode(longSyntax(20, true, -1).stream(), false);
    float[] a = signal(4, false), b = a.clone();
    previous.processICP(a); fresh.processICP(b);
    assertArrayEquals(b, a, 0);
  }

  @Test
  void fullPredictorBandHistoryIsBoundedAtEverySupportedFrequency() {
    for (int index = 0; index < 12; index++) {
      int asc = (1 << 11) | (index << 7) | (2 << 3); // Main / indexed rate / stereo
      var info = new ICSInfo(Decoder.create(new byte[] {(byte) (asc >>> 8), (byte) asc}).getConfig());
      info.decode(longSyntax(0, false, -1).stream(), false);
      info.processICP(signal(0, false));
      info.decode(new AacTnsTest.Bits().add(0, 1).add(2, 2).add(0, 1)
          .add(0, 4).add(127, 7).stream(), false);
      info.processICP(new float[1024]);
      info.decode(longSyntax(0, false, -1).stream(), false);
      float[] silent = new float[1024];
      info.processICP(silent);
      assertArrayEquals(new float[1024], silent, 0);
    }
  }

  @Test
  void reservedResetGroupsAreRejectedAndBoundaryGroupsRemainValid() {
    for (int group : new int[] {0, 31}) {
      var info = info();
      assertThrows(AACException.class, () -> info.decode(longSyntax(1, true, group).stream(), false));
    }
    for (int group : new int[] {1, 30}) {
      var info = info();
      info.decode(longSyntax(1, true, group).stream(), false);
      info.processICP(signal(0, false));
    }
  }

  @Test
  void realMainAccessUnitsMatchIndependentPcmThroughRelocationAndR8() throws Exception {
    var artifact = Path.of(System.getProperty("audioCodecJar"));
    try (var isolated = new URLClassLoader(new URL[] {artifact.toUri().toURL()},
        ClassLoader.getPlatformClassLoader())) {
      for (String name : new String[] {"mono", "stereo"}) {
        String mode = "aac-main/" + name;
        byte[] actual = ShadedCodecTest.packets(getClass().getClassLoader(), mode);
        assertArrayEquals(actual, ShadedCodecTest.packets(isolated, mode));
        var pcm = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        var ref = ByteBuffer.wrap(CodecFixtureTest.resource(mode + ".reference.s16le"))
            .order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        int channels = name.equals("mono") ? 1 : 2;
        assertEquals(ref.remaining() / channels * 2, pcm.remaining()); // JAAD duplicates mono
        for (int frame = 0; frame < pcm.remaining() / 2; frame++) {
          for (int channel = 0; channel < 2; channel++) {
            int expected = ref.get(frame * channels + (channels == 1 ? 0 : channel));
            assertTrue(Math.abs(pcm.get(frame * 2 + channel) - expected) <= 1,
                name + " independent reference mismatch at frame " + frame + " channel " + channel);
          }
        }
      }
    }
  }
}
