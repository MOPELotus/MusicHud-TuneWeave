package indi.mopelotus.musichud.audio.codec.internal.wma;

import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Forward-only ASF/WMA v1/v2 decoder. Owns its input, including on constructor failure. */
public final class WmaPcmDecoder implements PcmDecoder {
  private final AsfWmaInputStream stream;
  private final PcmFormat format;
  private volatile long framePosition;

  public WmaPcmDecoder(InputStream input) throws IOException {
    stream = new AsfWmaInputStream(input);
    var source = stream.format();
    format =
        new PcmFormat(
            source.sampleRate(),
            source.channels(),
            PcmFormat.Encoding.F32LE,
            source.channels() == 1 ? PcmFormat.Layout.MONO : PcmFormat.Layout.STEREO);
  }

  @Override
  public PcmFormat format() {
    return format;
  }

  @Override
  public long framePosition() {
    return framePosition;
  }

  /** Container timestamps describe consumed packets, independently of delivered PCM position. */
  public long lastPacketTimestampMillis() {
    return stream.lastPacketTimestampMillis();
  }

  public long durationMillis() {
    return stream.format().durationMillis();
  }

  @Override
  public int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    Objects.requireNonNull(target);
    if (maxFrames < 0
        || offset < 0
        || offset > target.length
        || maxFrames > (target.length - offset) / format.frameBytes())
      throw new IndexOutOfBoundsException("PCM target range");
    if (maxFrames == 0) return 0;
    int wanted = maxFrames * format.frameBytes();
    int count = 0;
    while (count < wanted) {
      int n = stream.read(target, offset + count, wanted - count);
      if (n < 0) break;
      count += n;
    }
    if (count % format.frameBytes() != 0) throw new EOFException("Truncated WMA PCM frame");
    if (count == 0) return -1;
    int frames = count / format.frameBytes();
    framePosition += frames;
    return frames;
  }

  @Override
  public void close() throws IOException {
    stream.close();
  }
}
