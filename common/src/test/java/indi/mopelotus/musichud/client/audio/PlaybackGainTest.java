package indi.mopelotus.musichud.client.audio;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PlaybackGainTest {
  @Test
  void vanillaMixIncludesBothMasterAndMusic() {
    assertEquals(.125f, PlaybackGain.calculate(50, true, false, .5f, .5f, 1), 1e-6);
    assertEquals(0, PlaybackGain.calculate(100, true, false, 0, 1, 1));
    assertEquals(0, PlaybackGain.calculate(100, true, false, 1, 0, 1));
  }

  @Test
  void disablingMixIgnoresVanillaButStillHonorsModMuteAndFade() {
    assertEquals(.6f, PlaybackGain.calculate(60, false, false, 0, 0, 1), 1e-6);
    assertEquals(0, PlaybackGain.calculate(100, false, true, 1, 1, 1));
    assertEquals(.15f, PlaybackGain.calculate(60, false, false, 0, 0, .25f), 1e-6);
    assertEquals(0, PlaybackGain.calculate(60, false, false, 1, 1, 0));
  }

  @Test
  void fadesStayMonotonicAndCannotSendNonfiniteOrOutOfRangeGain() {
    float prior = -1;
    for (int i = 0; i <= 100; i++) {
      float gain = PlaybackGain.calculate(80, true, false, .8f, .5f, i / 100f);
      assertTrue(gain >= prior);
      prior = gain;
    }
    assertEquals(.32f, prior, 1e-6);
    assertEquals(0, PlaybackGain.calculate(Float.NaN, false, false, 1, 1, 1));
    assertEquals(0, PlaybackGain.calculate(100, true, false, Float.NaN, 1, 1));
    assertEquals(0, PlaybackGain.calculate(100, false, false, 1, 1, Float.NaN));
    assertEquals(1, PlaybackGain.calculate(200, true, false, 2, 2, 2));
  }
}
