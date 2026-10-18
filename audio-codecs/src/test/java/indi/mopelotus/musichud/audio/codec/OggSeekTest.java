package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OggSeekTest {
  @ParameterizedTest
  @ValueSource(strings = {"vorbis", "opus"})
  void exactPcmGlobalOffsetAndSupersededResults(String codec) throws Exception {
    byte[] input = CodecFixtureTest.resource(codec + ".ogg");
    byte[] sequential;
    try (var d = CodecFixtureTest.open(codec)) {
      sequential = read(d);
    }
    var channels = new ArrayList<MemoryChannel>();
    try (var seeker =
        new OggSeekDecoder(
            () -> {
              var ch = new MemoryChannel(input);
              channels.add(ch);
              return ch;
            },
            input.length)) {
      assertEquals(4800, seeker.totalFrames());
      for (long target : new long[] {0, 1, 113, 1201, 4799, 4800}) {
        var result = seeker.seek(target);
        assertEquals(target, result.sourceFrame());
        assertEquals(4800, result.totalSourceFrames());
        assertTrue(seeker.isCurrent(result.statistics().generation()));
        try (var decoder = result.decoder()) {
          assertEquals(0, decoder.framePosition());
          assertArrayEquals(
              Arrays.copyOfRange(sequential, (int) target * 4, sequential.length), read(decoder));
          assertEquals(4800 - target, decoder.framePosition());
        }
      }
      var first = seeker.seek(100);
      var second = seeker.seek(200);
      assertFalse(seeker.isCurrent(first.statistics().generation()));
      assertTrue(seeker.isCurrent(second.statistics().generation()));
      try (var old = first.decoder();
          var current = second.decoder()) {
        assertThrows(IOException.class, () -> old.readFrames(new byte[4], 0, 1));
        assertTrue(current.readFrames(new byte[4], 0, 1) > 0);
      }
      var last = seeker.seek(100);
      seeker.cancel();
      try (var decoder = last.decoder()) {
        assertThrows(IOException.class, () -> decoder.readFrames(new byte[4], 0, 1));
      }
      assertThrows(IllegalArgumentException.class, () -> seeker.seek(-1));
    }
    for (var channel : channels) assertEquals(1, channel.closes);
  }

  @ParameterizedTest
  @ValueSource(strings = {"vorbis", "opus"})
  void cancellationRejectsLateChannelBytesAndClosesOnce(String codec) throws Exception {
    byte[] bytes = CodecFixtureTest.resource(codec + ".ogg");
    var reads = new AtomicInteger();
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var blocked = new AtomicReference<MemoryChannel>();
    try (var seeker =
            new OggSeekDecoder(
                () -> {
                  if (reads.getAndIncrement() == 0) return new MemoryChannel(bytes);
                  var ch =
                      new MemoryChannel(bytes) {
                        boolean waited;

                        public int read(ByteBuffer b) throws IOException {
                          if (!waited) {
                            waited = true;
                            entered.countDown();
                            try {
                              if (!release.await(5, TimeUnit.SECONDS))
                                throw new IOException("Test read timeout");
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                              throw new InterruptedIOException();
                            }
                          }
                          return copy(b); // Deliberately returns late bytes despite close.
                        }

                        public void close() {
                          super.close();
                          release.countDown();
                        }
                      };
                  blocked.set(ch);
                  return ch;
                },
                bytes.length);
        var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var result = executor.submit(() -> seeker.seek(1000));
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      seeker.cancel();
      var failure = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
      assertInstanceOf(IOException.class, failure.getCause());
      assertEquals(1, blocked.get().closes);
    } finally {
      release.countDown();
    }
    byte[] damaged = bytes.clone();
    damaged[damaged.length - 1] ^= 1;
    var channel = new MemoryChannel(damaged);
    assertThrows(IOException.class, () -> new OggSeekDecoder(() -> channel, damaged.length));
    assertEquals(1, channel.closes);
  }

  static byte[] read(PcmDecoder decoder) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] b = new byte[113 * decoder.format().frameBytes()];
    int n;
    while ((n = decoder.readFrames(b, 0, 113)) != -1) {
      assertTrue(n > 0);
      out.write(b, 0, n * decoder.format().frameBytes());
    }
    return out.toByteArray();
  }

  static class MemoryChannel implements SeekableByteChannel {
    final byte[] bytes;
    int cursor, closes;
    boolean open = true;

    MemoryChannel(byte[] bytes) {
      this.bytes = bytes;
    }

    int copy(ByteBuffer b) {
      if (cursor == bytes.length) return -1;
      int n = Math.min(b.remaining(), bytes.length - cursor);
      b.put(bytes, cursor, n);
      cursor += n;
      return n;
    }

    public int read(ByteBuffer b) throws IOException {
      if (!open) throw new ClosedChannelException();
      return copy(b);
    }

    public int write(ByteBuffer b) {
      throw new NonWritableChannelException();
    }

    public long position() {
      return cursor;
    }

    public SeekableByteChannel position(long p) throws IOException {
      if (!open) throw new ClosedChannelException();
      if (p < 0 || p > bytes.length) throw new IOException("Out of range");
      cursor = (int) p;
      return this;
    }

    public long size() {
      return bytes.length;
    }

    public SeekableByteChannel truncate(long size) {
      throw new NonWritableChannelException();
    }

    public boolean isOpen() {
      return open;
    }

    public void close() {
      if (open) {
        closes++;
        open = false;
      }
    }
  }
}
