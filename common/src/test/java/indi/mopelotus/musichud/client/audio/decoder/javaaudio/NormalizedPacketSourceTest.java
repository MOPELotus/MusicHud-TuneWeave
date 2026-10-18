package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import static org.junit.jupiter.api.Assertions.*;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.aac.TsAdtsPacketSource;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

class NormalizedPacketSourceTest {
  static final PacketSource.Format AAC =
      new PacketSource.Format("mp4a", new byte[] {0x11, (byte) 0x90});

  static PacketSource.Time t(long n, long d) {
    return new PacketSource.Time(n, d);
  }

  static PacketSource.Packet p(
      PacketSource.Time pts, PacketSource.Time duration, boolean disc, PacketSource.Format format) {
    return new PacketSource.Packet(
        format,
        pts,
        pts,
        duration,
        PacketSource.Time.ZERO,
        PacketSource.Time.ZERO,
        disc,
        ByteBuffer.wrap(new byte[] {1, 2}));
  }

  static void time(PacketSource.Time actual, long n, long d) {
    assertEquals(0, actual.compareValue(t(n, d)));
  }

  static final class Fake implements PacketSource {
    final List<Packet> packets;
    final long[] epochs;
    int index;
    long returnedEpoch;
    int closes;

    Fake(List<Packet> packets, long... epochs) {
      this.packets = packets;
      this.epochs = epochs;
    }

    public Track track() {
      return new Track(AAC, null, List.of(), Time.ZERO, Time.ZERO);
    }

    public Packet next() {
      if (index == packets.size()) return null;
      returnedEpoch = epochs[index];
      return packets.get(index++);
    }

    public Time seekMedia(Time target) throws IOException {
      throw new IOException();
    }

    public void close() {
      closes++;
    }
  }

  @Test
  void nonzeroOriginAndOrdinaryGapsArePreserved() throws Exception {
    var f =
        new Fake(List.of(p(t(10, 1), t(1, 2), false, AAC), p(t(11, 1), t(1, 2), false, AAC)), 3, 3);
    try (var source = new NormalizedPacketSource(f, () -> f.returnedEpoch)) {
      time(source.next().presentationTime(), 0, 1);
      time(source.next().presentationTime(), 1, 1);
      assertNull(source.next());
      assertTrue(source.track().edits().isEmpty());
      assertNull(source.track().mediaDuration());
    }
    assertEquals(1, f.closes);
  }

  @Test
  void configAndDiscontinuityFlagsDoNotInventEpochs() throws Exception {
    var other = new PacketSource.Format("mp4a", new byte[] {0x12, 0x10});
    var f =
        new Fake(
            List.of(
                p(t(10, 1), t(1024, 48000), false, AAC),
                p(t(11, 1), t(1024, 44100), true, other),
                p(t(12, 1), t(1024, 44100), true, other)),
            7,
            7,
            7);
    try (var source = new NormalizedPacketSource(f, () -> f.returnedEpoch)) {
      source.next();
      var second = source.next();
      time(second.presentationTime(), 1, 1);
      assertTrue(second.discontinuity());
      assertEquals(other, second.format());
      time(source.next().presentationTime(), 2, 1);
    }
  }

  @Test
  void genuineEpochStartsAtPreviousExactPacketEnd() throws Exception {
    var f =
        new Fake(
            List.of(
                p(t(126000, 90000), t(1024, 44100), true, AAC),
                p(t(7, 90000), t(1024, 48000), true, AAC),
                p(t(1927, 90000), t(1024, 48000), false, AAC)),
            2,
            3,
            3);
    try (var source = new NormalizedPacketSource(f, () -> f.returnedEpoch)) {
      time(source.next().presentationTime(), 0, 1);
      time(source.next().presentationTime(), 1024, 44100);
      var third = source.next();
      time(third.presentationTime(), 1024L * 48000 + 1024L * 44100, 44100L * 48000);
    }
  }

  @Test
  void discardDurationAndDtsRemainIntact() throws Exception {
    var packet =
        new PacketSource.Packet(
            AAC,
            t(19, 2),
            t(10, 1),
            t(1, 1),
            t(1, 10),
            t(1, 20),
            true,
            ByteBuffer.wrap(new byte[] {9}));
    var f = new Fake(List.of(packet), 1);
    try (var source = new NormalizedPacketSource(f, () -> f.returnedEpoch)) {
      var actual = source.next();
      time(actual.decodeTime(), -1, 2);
      time(actual.presentationTime(), 0, 1);
      assertEquals(packet.duration(), actual.duration());
      assertEquals(packet.discardStart(), actual.discardStart());
      assertEquals(packet.discardEnd(), actual.discardEnd());
      assertEquals(packet.encoded(), actual.encoded());
    }
  }

  @Test
  void bigIntegerOriginAvoidsIntermediateLongOverflow() throws Exception {
    var f =
        new Fake(
            List.of(
                p(t(Long.MIN_VALUE, 48000), t(1024, 48000), false, AAC),
                p(t(Long.MIN_VALUE + 1024, 48000), t(1024, 48000), false, AAC)),
            1,
            1);
    try (var source = new NormalizedPacketSource(f, () -> f.returnedEpoch)) {
      time(source.next().presentationTime(), 0, 1);
      time(source.next().presentationTime(), 1024, 48000);
    }
  }

  @Test
  void unrepresentableOutputAndUnknownClockFailExplicitly() throws Exception {
    var f =
        new Fake(
            List.of(
                p(t(Long.MIN_VALUE, 1), t(1, 1), false, AAC),
                p(t(Long.MAX_VALUE, 1), t(1, 1), false, AAC)),
            1,
            1);
    try (var source = new NormalizedPacketSource(f, () -> f.returnedEpoch)) {
      source.next();
      assertThrows(IOException.class, source::next);
    }
    for (var packet : List.of(p(null, t(1, 1), false, AAC), p(t(1, 1), null, false, AAC)))
      try (var source = new NormalizedPacketSource(new Fake(List.of(packet), 1))) {
        assertThrows(IOException.class, source::next);
      }
  }

  static byte[] adts(int payload) throws IOException {
    int size = 7 + payload;
    byte[] b = new byte[size];
    b[0] = (byte) 255;
    b[1] = (byte) 0xf1;
    b[2] = (byte) 0x4c;
    b[3] = (byte) (0x80 | (size >>> 11));
    b[4] = (byte) (size >>> 3);
    b[5] = (byte) ((size & 7) << 5 | 0x1f);
    b[6] = (byte) 0xfc;
    Arrays.fill(b, 7, b.length, (byte) 3);
    return b;
  }

  @Test
  void parserReadAheadEpochCannotRelabelReturnedFrame() throws Exception {
    byte[] frame = adts(9);
    var bytes = new ByteArrayOutputStream();
    bytes.write(Arrays.copyOfRange(frame, 10, frame.length));
    bytes.write(frame);
    var pes = new ArrayDeque<TsAdtsPacketSource.Pes>();
    pes.add(new TsAdtsPacketSource.Pes(Arrays.copyOf(frame, 10), 0, false, 15, 1));
    pes.add(new TsAdtsPacketSource.Pes(bytes.toByteArray(), 90000, false, 15, 2));
    try (var ts =
            new TsAdtsPacketSource(
                new TsAdtsPacketSource.PesInput() {
                  public TsAdtsPacketSource.Pes next() {
                    return pes.poll();
                  }

                  public void close() {}
                });
        var source = new NormalizedPacketSource(ts, ts::timelineEpoch)) {
      time(source.next().presentationTime(), 0, 1);
      assertEquals(1, ts.timelineEpoch());
      time(source.next().presentationTime(), 1024, 48000);
      assertEquals(2, ts.timelineEpoch());
    }
  }

  @Test
  void closeCancelsOwnedBlockedSourceAndRejectsLatePacket() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicInteger closes = new AtomicInteger();
    var blocked =
        new PacketSource() {
          public Track track() {
            return new Track(AAC, null, List.of(), Time.ZERO, Time.ZERO);
          }

          public Packet next() throws IOException {
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            return p(t(0, 1), t(1, 1), false, AAC);
          }

          public Time seekMedia(Time target) throws IOException {
            throw new IOException();
          }

          public void close() {
            closes.incrementAndGet();
            release.countDown();
          }
        };
    try (var source = new NormalizedPacketSource(blocked);
        var pool = Executors.newSingleThreadExecutor()) {
      var future = pool.submit(() -> assertThrows(IOException.class, source::next));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      source.close();
      future.get(2, TimeUnit.SECONDS);
      source.close();
      assertEquals(1, closes.get());
    }
  }
}
