package indi.mopelotus.musichud.audio.container.aac;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.audio.container.ts.*;
import java.io.*;
import java.nio.*;
import java.util.*;

public class AdtsBoundaryTest {
  static int checks;

  interface Call {
    void run() throws Exception;
  }

  static void ok(boolean b) {
    checks++;
    if (!b) throw new AssertionError(checks);
  }

  static void bad(Call c) throws Exception {
    checks++;
    try {
      c.run();
      throw new AssertionError("Expected IO " + checks);
    } catch (IOException expected) {
    }
  }

  static byte[] frame(int index, int channels, int seed, boolean crc) {
    int n = crc ? 13 : 11;
    byte[] b = new byte[n];
    b[0] = (byte) 255;
    b[1] = (byte) (crc ? 240 : 241);
    b[2] = (byte) (64 | (index << 2) | (channels >> 2));
    b[3] = (byte) ((channels << 6) | (n >> 11));
    b[4] = (byte) (n >> 3);
    b[5] = (byte) ((n << 5) | 31);
    b[6] = (byte) 252;
    for (int i = crc ? 9 : 7; i < n; i++) b[i] = (byte) (seed + i);
    return b;
  }

  static byte[] cat(byte[]... all) throws IOException {
    var o = new ByteArrayOutputStream();
    for (byte[] a : all) o.write(a);
    return o.toByteArray();
  }

  static TsAdtsPacketSource.Pes p(byte[] b, long pts, boolean d) {
    return new TsAdtsPacketSource.Pes(b, pts, d, 15);
  }

  static TsAdtsPacketSource ts(TsAdtsPacketSource.Pes... parts) throws IOException {
    return new TsAdtsPacketSource(
        new TsAdtsPacketSource.PesInput() {
          int i;

          public TsAdtsPacketSource.Pes next() {
            return i < parts.length ? parts[i++] : null;
          }

          public void close() {}
        });
  }

  @org.junit.jupiter.api.Test
  void framingOwnershipTimestampsAndEpochs() throws Exception {
    byte[] a = frame(4, 2, 10, false), b = frame(4, 2, 20, false), c = frame(4, 2, 30, false);
    try (var s =
        ts(
            p(cat(a, Arrays.copyOf(b, 5)), 90000, true),
            p(cat(Arrays.copyOfRange(b, 5, b.length), c), 180000, false))) {
      var x = s.next();
      var y = s.next();
      var z = s.next();
      ok(x.presentationTime().compareValue(new PacketSource.Time(1, 1)) == 0);
      ok(y.presentationTime().compareValue(new PacketSource.Time(45124, 44100)) == 0);
      ok(z.presentationTime().compareValue(new PacketSource.Time(2, 1)) == 0);
      ok(x.discontinuity());
      ok(!y.discontinuity());
      ok(s.next() == null);
      ok(z.duration().compareValue(new PacketSource.Time(1024, 44100)) == 0);
    }
    try (var s = new AdtsPacketSource(new ByteArrayInputStream(cat(a, frame(3, 1, 20, false))))) {
      var x = s.next();
      var y = s.next();
      ok(!x.format().equals(y.format()));
      ok(y.discontinuity());
      ok(y.presentationTime().compareValue(new PacketSource.Time(1024, 44100)) == 0);
      ok(y.duration().compareValue(new PacketSource.Time(1024, 48000)) == 0);
    }
    try (var s = new AdtsPacketSource(new ByteArrayInputStream(frame(4, 2, 3, true)))) {
      ok(s.next().encoded().remaining() == 4);
    }
    byte[] largeTag = new byte[2 * 1024 * 1024 + 10];
    largeTag[0] = 'I';
    largeTag[1] = 'D';
    largeTag[2] = '3';
    largeTag[3] = 4;
    largeTag[6] = 1;
    try (var source = new AdtsPacketSource(new ByteArrayInputStream(cat(largeTag, a)))) {
      ok(source.next().encoded().remaining() == 4);
    }
    byte[] tag = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 3, 0, 0, 0};
    try (var s = new AdtsPacketSource(new ByteArrayInputStream(cat(tag, a, tag, b)))) {
      ok(s.next().encoded().remaining() == 4);
      ok(s.next().presentationTime().compareValue(new PacketSource.Time(1024, 44100)) == 0);
      ok(s.next() == null);
    }
    try (var s = ts(p(a, 90000, false), p(b, Long.MIN_VALUE, true))) {
      ok(s.next().presentationTime() != null);
      var v = s.next();
      ok(v.discontinuity());
      ok(v.presentationTime() == null);
    }
    bad(
        () ->
            ts(
                p(Arrays.copyOf(a, 4), 90000, true),
                p(Arrays.copyOfRange(a, 4, a.length), 180000, true)));
    bad(
        () ->
            new TsAdtsPacketSource(
                new TsAdtsPacketSource.PesInput() {
                  public TsAdtsPacketSource.Pes next() {
                    return new TsAdtsPacketSource.Pes(a, 0, false, 17);
                  }

                  public void close() {}
                }));
    for (int len = 1; len < a.length; len++) {
      final byte[] v = Arrays.copyOf(a, len);
      bad(() -> new AdtsPacketSource(new ByteArrayInputStream(v)));
    }
    byte[] multi = a.clone();
    multi[6] |= 1;
    bad(() -> new AdtsPacketSource(new ByteArrayInputStream(multi)));
    byte[] freq = a.clone();
    freq[2] |= 60;
    bad(() -> new AdtsPacketSource(new ByteArrayInputStream(freq)));
    byte[] pce = a.clone();
    pce[3] = 0;
    bad(() -> new AdtsPacketSource(new ByteArrayInputStream(pce)));
    byte[] small = a.clone();
    small[4] = 0;
    small[5] = 0;
    bad(() -> new AdtsPacketSource(new ByteArrayInputStream(small)));
    int[] closed = {0};
    bad(
        () ->
            new AdtsPacketSource(
                new ByteArrayInputStream(multi) {
                  public void close() {
                    closed[0]++;
                  }
                }));
    ok(closed[0] == 1);
    try (var s =
        new AdtsPacketSource(
            new ByteArrayInputStream(a) {
              public int read(byte[] b, int o, int n) {
                return super.read(b, o, Math.min(n, 1));
              }
            })) {
      ok(s.next() != null);
      ok(s.next() == null);
      s.close();
      bad(s::next);
    }
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    int[] closes = {0};
    var blocking =
        new TsAdtsPacketSource.PesInput() {
          boolean first = true, done;

          public synchronized TsAdtsPacketSource.Pes next() throws IOException {
            if (first) {
              first = false;
              return p(a, 0, false);
            }
            entered.countDown();
            while (!done)
              try {
                wait();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
              }
            throw new IOException("Cancelled");
          }

          public synchronized void close() {
            done = true;
            closes[0]++;
            notifyAll();
          }
        };
    var source = new TsAdtsPacketSource(blocking);
    source.next();
    java.util.concurrent.atomic.AtomicReference<Throwable> outcome =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    source.next();
                    outcome.set(new AssertionError("Expected cancellation"));
                  } catch (Throwable expected) {
                    outcome.set(expected);
                  }
                });
    ok(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
    source.close();
    source.close();
    reader.join(2000);
    ok(!reader.isAlive());
    ok(outcome.get() instanceof IOException);
    ok(closes[0] == 1);
    bad(source::next);
    try (var epochs =
        ts(
            new TsAdtsPacketSource.Pes(Arrays.copyOf(a, 5), 90000, true, 15, 7),
            new TsAdtsPacketSource.Pes(
                cat(Arrays.copyOfRange(a, 5, a.length), b), 180000, false, 15, 8))) {
      ok(epochs.timelineEpoch() == 0);
      epochs.next();
      ok(epochs.timelineEpoch() == 7);
      epochs.next();
      ok(epochs.timelineEpoch() == 8);
      ok(epochs.next() == null);
      ok(epochs.timelineEpoch() == 8);
    }
    try (var epochs =
        ts(
            new TsAdtsPacketSource.Pes(a, 90000, true, 15, 10),
            new TsAdtsPacketSource.Pes(b, 0, true, 15, 11))) {
      ok(epochs.next().discontinuity());
      ok(epochs.timelineEpoch() == 10);
      ok(epochs.next().discontinuity());
      ok(epochs.timelineEpoch() == 11);
    }
    try (var epochs =
        ts(
            new TsAdtsPacketSource.Pes(a, 90000, false, 15, 20),
            new TsAdtsPacketSource.Pes(b, Long.MIN_VALUE, false, 15, 21))) {
      epochs.next();
      var second = epochs.next();
      ok(epochs.timelineEpoch() == 21);
      ok(!second.discontinuity());
      ok(second.presentationTime().compareValue(new PacketSource.Time(45124, 44100)) == 0);
      ok(second.duration().compareValue(new PacketSource.Time(1024, 44100)) == 0);
    }
    org.junit.jupiter.api.Assertions.assertEquals(58, checks);
  }
}
