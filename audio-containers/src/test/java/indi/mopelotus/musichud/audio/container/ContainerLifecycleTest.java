package indi.mopelotus.musichud.audio.container;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.container.info.AudioTrackInfoProvider;
import indi.mopelotus.musichud.audio.container.io.SeekableInputStream;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContainerLifecycleTest {
  @TempDir Path temporary;

  static class Channel implements SeekableByteChannel {
    final SeekableByteChannel delegate;
    int closes;

    Channel(Path p) throws IOException {
      delegate = Files.newByteChannel(p);
    }

    public int read(ByteBuffer b) throws IOException {
      return delegate.read(b);
    }

    public int write(ByteBuffer b) {
      throw new UnsupportedOperationException();
    }

    public long position() throws IOException {
      return delegate.position();
    }

    public SeekableByteChannel position(long p) throws IOException {
      delegate.position(p);
      return this;
    }

    public long size() throws IOException {
      return delegate.size();
    }

    public SeekableByteChannel truncate(long n) {
      throw new UnsupportedOperationException();
    }

    public boolean isOpen() {
      return delegate.isOpen();
    }

    public void close() throws IOException {
      closes++;
      delegate.close();
    }
  }

  static class Memory extends SeekableInputStream {
    final byte[] bytes;
    int position, closes;
    boolean closed;
    volatile boolean blocked;
    final CountDownLatch reading = new CountDownLatch(1);

    Memory(byte[] bytes) {
      super(bytes.length, 0);
      this.bytes = bytes;
    }

    public long getPosition() {
      return position;
    }

    protected void seekHard(long value) throws IOException {
      if (value < 0 || value > bytes.length) throw new EOFException();
      position = (int) value;
    }

    public boolean canSeekHard() {
      return true;
    }

    public List<AudioTrackInfoProvider> getTrackInfoProviders() {
      return List.of();
    }

    public synchronized int read() throws IOException {
      while (blocked && !closed) {
        reading.countDown();
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
      }
      if (closed) throw new IOException("Closed");
      return position == bytes.length ? -1 : bytes[position++] & 255;
    }

    public int read(byte[] target, int offset, int length) throws IOException {
      if (length == 0) return 0;
      int first = read();
      if (first < 0) return -1;
      target[offset] = (byte) first;
      int count = Math.min(length - 1, bytes.length - position);
      System.arraycopy(bytes, position, target, offset + 1, count);
      position += count;
      return count + 1;
    }

    public synchronized void close() {
      closes++;
      closed = true;
      notifyAll();
    }
  }

  @Test
  void mp4OwnsBothInputsAndClosesOnceOnSuccessAndFailure() throws Exception {
    for (boolean valid : List.of(true, false)) {
      byte[] data = ContainerFixturesTest.fixture("aac.m4a");
      if (!valid) data = Arrays.copyOf(data, 10);
      Path file = Files.write(temporary.resolve("input-" + valid), data);
      var channel = new Channel(file);
      var media = new Channel(file);
      try (var source = new Mp4PacketSource(channel, media)) {
        assertTrue(valid);
        while (source.next() != null) {}
        source.close();
      } catch (IOException e) {
        if (valid) throw e;
      }
      assertEquals(1, channel.closes);
      assertEquals(1, media.closes);
    }
  }

  @Test
  void invalidInputPairClosesTransferredChannelsOnce() throws Exception {
    Path file =
        Files.write(temporary.resolve("pair.m4a"), ContainerFixturesTest.fixture("aac.m4a"));
    var metadata = new Channel(file);
    assertThrows(IOException.class, () -> new Mp4PacketSource(metadata, null));
    assertEquals(1, metadata.closes);
    var media = new Channel(file);
    assertThrows(IOException.class, () -> new Mp4PacketSource(null, media));
    assertEquals(1, media.closes);
    var alias = new Channel(file);
    assertThrows(IOException.class, () -> new Mp4PacketSource(alias, alias));
    assertEquals(1, alias.closes);
  }

  static class BlockingChannel extends Channel {
    final CountDownLatch reading = new CountDownLatch(1);
    boolean closed;

    BlockingChannel(Path path) throws IOException {
      super(path);
    }

    @Override
    public synchronized int read(ByteBuffer bytes) throws IOException {
      reading.countDown();
      while (!closed) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException();
        }
      }
      throw new IOException("Closed blocked channel");
    }

    @Override
    public synchronized void close() throws IOException {
      closed = true;
      super.close();
      notifyAll();
    }
  }

  @Test
  void concurrentCloseUnblocksMp4MediaRead() throws Exception {
    Path file =
        Files.write(temporary.resolve("blocked.m4a"), ContainerFixturesTest.fixture("aac.m4a"));
    var metadata = new Channel(file);
    var media = new BlockingChannel(file);
    try (var source = new Mp4PacketSource(metadata, media);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var task = executor.submit(() -> assertThrows(IOException.class, source::next));
      assertTrue(media.reading.await(2, TimeUnit.SECONDS));
      source.close();
      source.close();
      task.get(2, TimeUnit.SECONDS);
      assertEquals(1, metadata.closes);
      assertEquals(1, media.closes);
    }
  }

  @Test
  void truncatedMatroskaConstructionReleasesOwnership() throws Exception {
    byte[] valid = ContainerFixturesTest.fixture("opus.webm");
    for (int length = 0; length < 128; length++) {
      var input = new Memory(Arrays.copyOf(valid, length));
      try (var source = new MatroskaPacketSource(input)) {
        while (source.next() != null) {}
      } catch (IOException expected) {
      }
      assertEquals(1, input.closes, "Header length " + length);
    }
  }

  @Test
  void concurrentCloseUnblocksPacketReadAndClosesOnce() throws Exception {
    var input = new Memory(ContainerFixturesTest.fixture("opus.webm"));
    try (var source = new MatroskaPacketSource(input);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      input.blocked = true;
      var task = executor.submit(() -> assertThrows(IOException.class, source::next));
      assertTrue(input.reading.await(2, TimeUnit.SECONDS), "Reader did not block");
      source.close();
      source.close();
      task.get(2, TimeUnit.SECONDS);
      assertEquals(1, input.closes);
    }
  }
}
