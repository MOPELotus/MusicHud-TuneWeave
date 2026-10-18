package indi.mopelotus.musichud.audio.codec.internal;

import javazoom.jl.decoder.Obuffer;

/** Shared JLayer PCM gain normalization for sequential and indexed decoding. */
final class JLayerPcmBuffer extends Obuffer {
  final short[] samples = new short[2304];
  final int[] positions = new int[2];
  final int channels;

  JLayerPcmBuffer(int channels) {
    this.channels = channels;
    clearBuffer();
  }

  int length() {
    return positions[0];
  }

  public void append(int channel, short value) {
    samples[positions[channel]] = value;
    positions[channel] += channels;
  }

  public void appendSamples(int channel, float[] values) {
    int pos = positions[channel];
    for (int i = 0; i < 32; i++) {
      int value = Math.round(values[i] * (32768.0f / 32700.0f));
      samples[pos] = (short) Math.max(-32768, Math.min(32767, value));
      pos += channels;
    }
    positions[channel] = pos;
  }

  public void clearBuffer() {
    positions[0] = 0;
    positions[1] = 1;
  }

  public void writeBuffer(int ignored) {}

  public void close() {}

  public void setStopFlag() {}
}
