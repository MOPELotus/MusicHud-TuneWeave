package indi.mopelotus.musichud.client.audio;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.client.audio.decoder.AudioDecoder;
import org.junit.jupiter.api.Test;

class PlaybackSeekPolicyTest {
  private static final class Decoder implements AudioDecoder {
    boolean qualified, successful = true, wrongPosition;
    int seeks;
    long position;

    public boolean supportsFastSeek() {
      return qualified;
    }

    public boolean seekToFrame(long target) {
      seeks++;
      position = wrongPosition ? target - 1 : target;
      return successful;
    }

    public long getPositionFrames() {
      return position;
    }

    public int getSampleRate() {
      return 48000;
    }

    public int getFrameSize() {
      return 4;
    }

    public int getFormat() {
      return 0;
    }

    public byte[] readChunk(long size) {
      throw new AssertionError("Policy must not consume PCM");
    }

    public void close() {}
  }

  @Test
  void asynchronousLegacyBackendNeverReceivesAPreciseSeek() {
    var decoder = new Decoder();
    assertFalse(PlaybackSeekPolicy.advance(decoder, 0, 48000 * 120));
    assertEquals(0, decoder.seeks);
  }

  @Test
  void smallCorrectionsKeepIncrementalPcmWithoutReopening() {
    var decoder = new Decoder();
    decoder.qualified = true;
    assertFalse(PlaybackSeekPolicy.advance(decoder, 48000, 48001));
    assertFalse(PlaybackSeekPolicy.advance(decoder, 48000, 0));
    assertEquals(0, decoder.seeks);
  }

  @Test
  void aQualifiedJumpCommitsTheExactGlobalFrame() {
    var decoder = new Decoder();
    decoder.qualified = true;
    assertTrue(PlaybackSeekPolicy.advance(decoder, 100, 48000 * 120 + 17));
    assertEquals(48000 * 120 + 17, decoder.position);
    assertEquals(1, decoder.seeks);
  }

  @Test
  void failedOrApproximateSeekCannotAdvanceThePlaybackClock() {
    var decoder = new Decoder();
    decoder.qualified = true;
    decoder.successful = false;
    assertThrows(
        IllegalStateException.class, () -> PlaybackSeekPolicy.advance(decoder, 0, 48000 * 120));
    decoder.successful = true;
    decoder.wrongPosition = true;
    assertThrows(
        IllegalStateException.class, () -> PlaybackSeekPolicy.advance(decoder, 0, 48000 * 120));
  }
}
