package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.concurrent.atomic.*;

/** Two independent parser cursors over one response; never combine unvalidated HTTP responses. */
public final class SharedChannelViews {
  private final SeekableByteChannel input;
  private final Object io = new Object();
  private final AtomicInteger owners = new AtomicInteger(2);
  private final SeekableByteChannel metadata = new View(), media = new View();

  public SharedChannelViews(SeekableByteChannel owned) {
    input = owned;
  }

  public SeekableByteChannel metadata() {
    return metadata;
  }

  public SeekableByteChannel media() {
    return media;
  }

  private final class View implements SeekableByteChannel {
    final AtomicBoolean live = new AtomicBoolean(true);
    long position;

    private void check() throws ClosedChannelException {
      if (!live.get()) throw new ClosedChannelException();
    }

    public int read(ByteBuffer bytes) throws IOException {
      synchronized (io) {
        check();
        input.position(position);
        int n = input.read(bytes);
        if (n > 0) position += n;
        return n;
      }
    }

    public long position() throws IOException {
      synchronized (io) {
        check();
        return position;
      }
    }

    public SeekableByteChannel position(long next) throws IOException {
      synchronized (io) {
        check();
        if (next < 0) throw new IllegalArgumentException();
        position = next;
        return this;
      }
    }

    public long size() throws IOException {
      check();
      return input.size();
    }

    public boolean isOpen() {
      return live.get() && input.isOpen();
    }

    public int write(ByteBuffer bytes) {
      throw new NonWritableChannelException();
    }

    public SeekableByteChannel truncate(long value) {
      throw new NonWritableChannelException();
    }

    public void close() throws IOException {
      if (live.compareAndSet(true, false) && owners.decrementAndGet() == 0) input.close();
    }
  }
}
