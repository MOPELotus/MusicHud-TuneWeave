package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.PcmFormat;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.PresentedDecoder;
import indi.mopelotus.musichud.client.audio.decoder.javaaudio.SpeakerOrder;
import java.io.*;

/** Pure Java bridge: uses the existing device conversion, never changes the presentation clock. */
public final class JavaStreamDecoder implements AudioDecoder {
  private final PresentedDecoder input;
  private final PcmOutput output;
  private final PcmFormat format;
  private final boolean ieee;
  private final java.util.concurrent.atomic.AtomicBoolean closed =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final AutoCloseable resource;
  private final SpeakerOrder speakers;

  public JavaStreamDecoder(
      PresentedDecoder input, boolean floatSupported, boolean multichannelSupported) {
    this(input, floatSupported, multichannelSupported, SpeakerOrder.forFormat(input.format()));
  }

  public JavaStreamDecoder(
      PresentedDecoder input,
      boolean floatSupported,
      boolean multichannelSupported,
      SpeakerOrder speakers) {
    this(input, floatSupported, multichannelSupported, speakers, () -> {});
  }

  public JavaStreamDecoder(
      PresentedDecoder input,
      boolean floatSupported,
      boolean multichannelSupported,
      SpeakerOrder speakers,
      AutoCloseable resource) {
    this.resource = java.util.Objects.requireNonNull(resource);
    this.input = input;
    format = input.format();
    this.speakers = speakers;
    if (speakers.sourceBits().length != format.channels())
      throw new IllegalArgumentException("Speaker count mismatch");
    ieee = format.encoding() == PcmFormat.Encoding.F32LE;
    output =
        new PcmOutput(
            format.channels(),
            format.encoding().bytes * 8,
            format.sampleRate(),
            floatSupported,
            speakers.mask(),
            multichannelSupported);
  }

  public byte[] readChunk(long maxSize) {
    if (closed.get() || maxSize <= 0) return null;
    int frames = (int) Math.min(16384, maxSize / output.frameSize());
    if (frames == 0) throw new IllegalArgumentException("Chunk smaller than one PCM frame");
    byte[] raw = new byte[Math.multiplyExact(frames, format.frameBytes())];
    try {
      int n = input.readFrames(raw, 0, frames);
      if (n < 0) return null;
      if (n < frames) raw = java.util.Arrays.copyOf(raw, n * format.frameBytes());
      return output.convert(speakers.canonical(raw, format.encoding().bytes), ieee);
    } catch (IOException error) {
      throw new UncheckedIOException(error);
    }
  }

  public boolean seekToMillis(long millis) {
    if (millis < 0) return false;
    try {
      return input.seekFrame(
          Math.addExact(
              Math.multiplyExact(millis / 1000, format.sampleRate()),
              millis % 1000 * format.sampleRate() / 1000));
    } catch (IOException | ArithmeticException error) {
      return false;
    }
  }

  public long getPositionMillis() {
    long frames = input.framePosition();
    return Math.addExact(
        Math.multiplyExact(frames / format.sampleRate(), 1000),
        frames % format.sampleRate() * 1000 / format.sampleRate());
  }

  public boolean hasDownmixedChannels() {
    return input.hasDownmixedChannels()
        || getFrameSize() / (output.floating ? 4 : 2) < format.channels();
  }

  public int getFormat() {
    return output.format();
  }

  public int getSampleRate() {
    return format.sampleRate();
  }

  public int getFrameSize() {
    return output.frameSize();
  }

  public SampleEncoding getSampleEncoding() {
    return output.floating ? SampleEncoding.PCM_F32_LE : SampleEncoding.PCM_S16_LE;
  }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    try {
      input.close();
    } finally {
      try {
        resource.close();
      } catch (Exception error) {
        throw new RuntimeException("Unable to close media resource", error);
      }
    }
  }
}
