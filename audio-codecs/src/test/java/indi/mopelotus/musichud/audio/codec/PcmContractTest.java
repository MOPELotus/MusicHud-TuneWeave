package indi.mopelotus.musichud.audio.codec;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PcmContractTest {
  @Test
  void integerAndFloatPrecisionBoundaries() {
    assertArrayEquals(
        new byte[] {0, -128, 0, -1, 0, 0, 0, 1, 0, 127},
        PcmConversions.toS16(new byte[] {-128, -1, 0, 1, 127}, format(PcmFormat.Encoding.S8)));
    byte[] floats =
        ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putFloat(-1)
            .putFloat(1)
            .putFloat(Float.NaN)
            .putFloat(Float.POSITIVE_INFINITY)
            .putFloat(Float.NEGATIVE_INFINITY)
            .putFloat(1f / 32768)
            .array();
    assertArrayEquals(
        new byte[] {0, -128, -1, 127, 0, 0, -1, 127, 0, -128, 1, 0},
        PcmConversions.toS16(floats, format(PcmFormat.Encoding.F32LE)));
    byte[] integers =
        ByteBuffer.allocate(16)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Integer.MIN_VALUE)
            .putInt(-65536)
            .putInt(65536)
            .putInt(Integer.MAX_VALUE)
            .array();
    assertArrayEquals(
        new byte[] {0, -128, -1, -1, 1, 0, -1, 127},
        PcmConversions.toS16(integers, format(PcmFormat.Encoding.S32LE)));
    assertArrayEquals(
        new byte[] {0, -128, -1, -1, 1, 0, -1, 127},
        PcmConversions.toS16(
            new byte[] {0, 0, -128, 0, -1, -1, 0, 1, 0, -1, -1, 127},
            format(PcmFormat.Encoding.S24LE)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            PcmConversions.toS16(
                new byte[3],
                new PcmFormat(48000, 2, PcmFormat.Encoding.S16LE, PcmFormat.Layout.STEREO)));
  }

  @Test
  void closeRejectsBlockedLateChunkAndClosesExactlyOnce() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var closed = new AtomicInteger();
    var decoder =
        new ChunkDecoder(
            format(PcmFormat.Encoding.S16LE),
            new ChunkDecoder.Core() {
              public byte[] next() throws IOException {
                entered.countDown();
                try {
                  release.await();
                } catch (InterruptedException e) {
                  throw new IOException(e);
                }
                return new byte[] {1, 2};
              }

              public void close() {
                closed.incrementAndGet();
                release.countDown();
              }
            });
    try (var executor = Executors.newSingleThreadExecutor()) {
      var read =
          executor.submit(
              () -> assertThrows(IOException.class, () -> decoder.readFrames(new byte[2], 0, 1)));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      decoder.close();
      decoder.cancel();
      read.get(2, TimeUnit.SECONDS);
      assertEquals(0, decoder.framePosition());
      assertEquals(1, closed.get());
    }
  }

  @Test
  void invalidChunksAndRuntimeCodecFailureAreTerminal() throws Exception {
    for (int size : new int[] {0, 1, 16 * 1024 * 1024 + 1}) {
      var closed = new AtomicInteger();
      var decoder =
          new ChunkDecoder(
              format(PcmFormat.Encoding.S16LE),
              new ChunkDecoder.Core() {
                public byte[] next() {
                  return new byte[size];
                }

                public void close() {
                  closed.incrementAndGet();
                }
              });
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[2], 0, 1));
      decoder.close();
      assertEquals(1, closed.get());
      assertThrows(IOException.class, () -> decoder.readFrames(new byte[2], 0, 1));
    }
    var closed = new AtomicInteger();
    var decoder =
        new ChunkDecoder(
            format(PcmFormat.Encoding.S16LE),
            new ChunkDecoder.Core() {
              public byte[] next() {
                throw new IllegalStateException("Malformed codec state");
              }

              public void close() {
                closed.incrementAndGet();
              }
            });
    assertThrows(IOException.class, () -> decoder.readFrames(new byte[2], 0, 1));
    assertEquals(1, closed.get());
  }

  @Test
  void compressedMetadataAndPacketsAreImmutableAndBounded() {
    byte[] config = {1, 2};
    var format = new PacketSource.Format("mp4a", config);
    config[0] = 9;
    assertArrayEquals(new byte[] {1, 2}, format.codecPrivate());
    format.codecPrivate()[0] = 8;
    assertEquals(format, new PacketSource.Format("mp4a", new byte[] {1, 2}));
    assertEquals(format.hashCode(), new PacketSource.Format("mp4a", new byte[] {1, 2}).hashCode());
    byte[] bytes = {3, 4};
    var packet =
        new PacketSource.Packet(
            format,
            null,
            null,
            null,
            PacketSource.Time.ZERO,
            PacketSource.Time.ZERO,
            false,
            ByteBuffer.wrap(bytes));
    bytes[0] = 7;
    assertEquals(3, packet.encoded().get());
    assertEquals(3, packet.encoded().get());
    assertThrows(ReadOnlyBufferException.class, () -> packet.encoded().put((byte) 0));
    assertThrows(IllegalArgumentException.class, () -> new PacketSource.Time(0, 0));
    assertTrue(
        new PacketSource.Time(Long.MAX_VALUE, 2)
                .compareValue(new PacketSource.Time(Long.MAX_VALUE, 3))
            > 0);
    assertThrows(
        IllegalArgumentException.class,
        () -> new PacketSource.Format("mp4a", new byte[PacketSource.MAX_CODEC_PRIVATE_BYTES + 1]));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PacketSource.Packet(
                format,
                null,
                null,
                null,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                false,
                ByteBuffer.allocate(PacketSource.MAX_PACKET_BYTES + 1)));
  }

  @Test
  void malformedStreamHeadersReleaseTheirOwnedInputOnce() throws Exception {
    for (int length = 0; length < 34; length++) {
      byte[] header = new byte[length];
      Arrays.fill(header, (byte) 0x55);
      for (int codec = 0; codec < 3; codec++) {
        var closes = new AtomicInteger();
        var input =
            new ByteArrayInputStream(header) {
              public void close() {
                closes.incrementAndGet();
              }
            };
        final int selected = codec;
        assertThrows(
            IOException.class,
            () -> {
              try (var ignored =
                  switch (selected) {
                    case 0 -> JavaCodecs.mp3(input);
                    case 1 -> JavaCodecs.ogg(input);
                    default -> JavaCodecs.wavpack(input);
                  }) {}
            });
        assertEquals(1, closes.get(), "Constructor must release its transferred input");
      }
    }
  }

  private static PcmFormat format(PcmFormat.Encoding e) {
    return new PcmFormat(48000, 1, e, PcmFormat.Layout.MONO);
  }
}
