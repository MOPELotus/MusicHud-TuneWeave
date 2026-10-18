package indi.mopelotus.musichud.audio.codec;

import java.io.IOException;

/**
 * One logical stream, fixed format. Single reader; close may concurrently cancel it. Positions
 * count delivered PCM frames, never compressed packets or bytes. A new chained stream requires a
 * new decoder/format; concatenating differing formats is forbidden.
 */
public interface PcmDecoder extends AutoCloseable {
  PcmFormat format();

  long framePosition();

  /** Writes complete interleaved frames. Zero only for a zero-frame request; -1 at EOF. */
  int readFrames(byte[] target, int offset, int maxFrames) throws IOException;

  void close() throws IOException;

  default void cancel() throws IOException {
    close();
  }
}
