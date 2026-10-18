package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

/** Preserves a source fold-down decision across a stable stereo presentation boundary. */
public interface PcmChannelConversion {
  boolean hasDownmixedChannels();
}
