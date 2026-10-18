package indi.mopelotus.musichud.client.audio;

import indi.mopelotus.musichud.client.audio.decoder.AudioDecoder;

/** Long synchronization jumps use only decoders that commit an exact delivered-frame position. */
final class PlaybackSeekPolicy {
  private PlaybackSeekPolicy() {}

  static boolean advance(AudioDecoder decoder, long currentFrame, long targetFrame) {
    if (targetFrame <= currentFrame
        || targetFrame - currentFrame < 2L * decoder.getSampleRate()
        || !decoder.supportsFastSeek()) return false;
    if (!decoder.seekToFrame(targetFrame) || decoder.getPositionFrames() != targetFrame)
      throw new IllegalStateException("Audio decoder could not commit an exact seek");
    return true;
  }
}
