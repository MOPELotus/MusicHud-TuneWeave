package indi.mopelotus.musichud.audio.codec.internal.ape;

import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Forward-only APE adapter. Owns its input, including when opening fails. */
public final class ApePcmDecoder implements PcmDecoder {
  private final ApeDecoder decoder;
  private final PcmFormat format;
  private final AtomicBoolean closed = new AtomicBoolean();
  private byte[] pending;
  private int pendingOffset;
  private volatile long framePosition;
  private final Object publication = new Object();

  public ApePcmDecoder(InputStream source) throws IOException {
    decoder = ApeDecoder.openForwardStream(Objects.requireNonNull(source));
    format =
        new PcmFormat(
            decoder.sampleRate,
            decoder.channels,
            switch (decoder.bits) {
              case 8 -> PcmFormat.Encoding.S8;
              case 16 -> PcmFormat.Encoding.S16LE;
              case 24 -> PcmFormat.Encoding.S24LE;
              default -> throw new AssertionError("Validated APE bit depth");
            },
            decoder.channels == 1 ? PcmFormat.Layout.MONO : PcmFormat.Layout.STEREO);
  }

  @Override
  public PcmFormat format() {
    return format;
  }

  @Override
  public long framePosition() {
    return framePosition;
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) throw new IOException("APE decoder closed");
  }

  @Override
  public synchronized int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    Objects.requireNonNull(target);
    if (maxFrames < 0) throw new IllegalArgumentException("Negative frame count");
    int bytes = Math.multiplyExact(maxFrames, format.frameBytes());
    Objects.checkFromIndexSize(offset, bytes, target.length);
    ensureOpen();
    if (maxFrames == 0) return 0;
    if (pending == null || pendingOffset == pending.length) {
      try {
        pending = decoder.readFrame(); // The core checks the entire frame CRC before exposing PCM.
      } catch (IOException | RuntimeException error) {
        IOException failure =
            error instanceof IOException io ? io : new IOException("Malformed APE frame", error);
        try {
          close();
        } catch (IOException closing) {
          failure.addSuppressed(closing);
        }
        throw failure;
      }
      pendingOffset = 0;
      ensureOpen();
      if (pending == null) return -1;
      if (decoder.bits == 8) {
        // APE stores unsigned eight-bit samples; the common PCM contract is signed.
        for (int i = 0; i < pending.length; i++) pending[i] ^= (byte) 0x80;
      }
    }
    synchronized (publication) {
      ensureOpen();
      int count = Math.min(bytes, pending.length - pendingOffset);
      System.arraycopy(pending, pendingOffset, target, offset, count);
      pendingOffset += count;
      int frames = count / format.frameBytes();
      framePosition += frames;
      return frames;
    }
  }

  @Override
  public void close() throws IOException {
    boolean owner;
    synchronized (publication) {
      owner = closed.compareAndSet(false, true);
    }
    if (owner) decoder.close();
  }
}
