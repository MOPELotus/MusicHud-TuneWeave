package indi.mopelotus.musichud.audio.codec;

import java.io.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thin bounded bridge around synchronous codec chunks. Owns core and its input. */
final class ChunkDecoder implements PcmDecoder {
  public interface Core extends AutoCloseable {
    /** Complete PCM frames, or null at EOF; empty chunks are forbidden. */
    byte[] next() throws IOException;

    void close() throws IOException;
  }

  private final PcmFormat format;
  private final Core core;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object publication = new Object();
  private byte[] pending;
  private int cursor;
  private volatile long position;

  public ChunkDecoder(PcmFormat format, Core core) {
    this(format, core, 0);
  }

  ChunkDecoder(PcmFormat format, Core core, long initialPosition) {
    if (initialPosition < 0) throw new IllegalArgumentException("Negative initial frame position");
    position = initialPosition;
    this.format = Objects.requireNonNull(format);
    this.core = Objects.requireNonNull(core);
  }

  public PcmFormat format() {
    return format;
  }

  public long framePosition() {
    return position;
  }

  public int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    Objects.requireNonNull(target);
    if (maxFrames < 0) throw new IllegalArgumentException("Negative frame request");
    int bytes = Math.multiplyExact(maxFrames, format.frameBytes());
    Objects.checkFromIndexSize(offset, bytes, target.length);
    if (maxFrames == 0) return 0;
    ensureOpen();
    if (pending == null || cursor == pending.length) {
      byte[] next;
      try {
        next = core.next();
      } catch (IOException e) {
        throw fail(e);
      } catch (RuntimeException e) {
        throw fail(new IOException("Codec failed", e));
      }
      ensureOpen();
      if (next == null) {
        pending = null;
        cursor = 0;
        return -1;
      }
      if (next.length == 0
          || next.length % format.frameBytes() != 0
          || next.length > 16 * 1024 * 1024)
        throw fail(new IOException("Invalid PCM chunk boundary or size"));
      pending = next;
      cursor = 0;
    }
    synchronized (publication) {
      ensureOpen();
      int n = Math.min(bytes, pending.length - cursor);
      System.arraycopy(pending, cursor, target, offset, n);
      cursor += n;
      position = Math.addExact(position, n / format.frameBytes());
      return n / format.frameBytes();
    }
  }

  private IOException fail(IOException error) {
    try {
      close();
    } catch (IOException close) {
      error.addSuppressed(close);
    }
    return error;
  }

  private void ensureOpen() throws IOException {
    if (closed.get()) throw new IOException("Decoder closed");
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedIOException("Decoder interrupted");
  }

  public void close() throws IOException {
    boolean owner;
    synchronized (publication) {
      owner = closed.compareAndSet(false, true);
    }
    if (owner) core.close();
    // Do not mutate reader-owned pending/cursor while a read is running.
  }
}
