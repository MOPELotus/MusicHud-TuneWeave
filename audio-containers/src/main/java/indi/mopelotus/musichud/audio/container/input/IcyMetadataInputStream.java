package indi.mopelotus.musichud.audio.container.input;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Removes RFC-style icy-metaint blocks. Owns input on entry, including constructor failure.
 * Metadata delivery is optional, bounded and synchronous.
 */
public final class IcyMetadataInputStream extends InputStream {
  private final InputStream input;
  private final int interval;
  private final Consumer<byte[]> metadata;
  private int remaining;
  private volatile boolean closed;
  private boolean eof;
  private final Object publication = new Object();

  public IcyMetadataInputStream(InputStream input, int interval, Consumer<byte[]> metadata) {
    this.input = Objects.requireNonNull(input);
    if (interval < 1 || interval > 16 * 1024 * 1024) {
      IllegalArgumentException invalid = new IllegalArgumentException("Invalid icy-metaint");
      try {
        input.close();
      } catch (IOException failure) {
        invalid.addSuppressed(failure);
      }
      throw invalid;
    }
    this.interval = interval;
    remaining = interval;
    this.metadata = metadata;
  }

  public static int parseInterval(String value) throws IOException {
    if (value == null) return 0;
    if (!value.matches("[0-9]{1,8}")) throw new IOException("Invalid icy-metaint");
    try {
      int n = Integer.parseInt(value);
      if (n < 1 || n > 16 * 1024 * 1024) throw new NumberFormatException();
      return n;
    } catch (NumberFormatException e) {
      throw new IOException("Invalid icy-metaint", e);
    }
  }

  private void check() throws IOException {
    if (closed) throw new IOException("ICY input closed");
  }

  @Override
  public int read() throws IOException {
    byte[] b = new byte[1];
    int n = read(b, 0, 1);
    return n < 0 ? -1 : b[0] & 255;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    Objects.checkFromIndexSize(off, len, b.length);
    if (len == 0) return 0;
    check();
    if (eof) return -1;
    if (remaining == 0) {
      int size = input.read();
      check();
      // Clean EOF exactly at an audio interval boundary is allowed when no following metadata block
      // exists.
      if (size < 0) {
        eof = true;
        return -1;
      }
      byte[] raw = new byte[size * 16];
      int offset = 0;
      while (offset < raw.length) {
        int n = input.read(raw, offset, raw.length - offset);
        check();
        if (n < 0) throw new EOFException("Truncated ICY metadata");
        if (n == 0) throw new IOException("Non-progressing ICY metadata");
        offset += n;
      }
      check();
      if (raw.length != size * 16) throw new EOFException("Truncated ICY metadata");
      if (metadata != null && size > 0) {
        int end = raw.length;
        while (end > 0 && raw[end - 1] == 0) end--;
        synchronized (publication) {
          check();
          metadata.accept(Arrays.copyOf(raw, end));
        }
        check();
      }
      remaining = interval;
    }
    int n = input.read(b, off, Math.min(len, remaining));
    check();
    if (n < 0) {
      eof = true;
      return -1;
    }
    if (n == 0) throw new IOException("Non-progressing ICY input");
    remaining -= n;
    return n;
  }

  @Override
  public long skip(long n) throws IOException {
    if (n <= 0) return 0;
    byte[] scratch = new byte[(int) Math.min(8192, n)];
    long done = 0;
    while (done < n) {
      int got = read(scratch, 0, (int) Math.min(scratch.length, n - done));
      if (got < 0) break;
      done += got;
    }
    return done;
  }

  @Override
  public int available() throws IOException {
    check();
    return Math.min(remaining, input.available());
  }

  @Override
  public void close() throws IOException {
    boolean release;
    synchronized (publication) {
      release = !closed;
      closed = true;
    }
    if (release) input.close();
  }
}
