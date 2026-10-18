package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.*;
import java.io.*;
import java.math.*;
import java.nio.*;

class QuantizedPcmClockTest {
  static int assertions;

  static void yes(boolean good, String message) {
    assertions++;
    if (!good) throw new AssertionError(message);
  }

  static PacketSource.Time t(long n, long d) {
    return new PacketSource.Time(n, d);
  }

  static PacketSource.Time plus(PacketSource.Time a, PacketSource.Time b) {
    BigInteger n =
        BigInteger.valueOf(a.ticks())
            .multiply(BigInteger.valueOf(b.timescale()))
            .add(BigInteger.valueOf(b.ticks()).multiply(BigInteger.valueOf(a.timescale())));
    BigInteger d = BigInteger.valueOf(a.timescale()).multiply(BigInteger.valueOf(b.timescale()));
    BigInteger g = n.gcd(d);
    return t(n.divide(g).longValueExact(), d.divide(g).longValueExact());
  }

  static PacketSource.Time minus(PacketSource.Time a, PacketSource.Time b) {
    return plus(a, t(-b.ticks(), b.timescale()));
  }

  static PacketSource.Packet p(
      PacketSource.Time pts, PacketSource.Time duration, boolean disc, int payload) {
    return new PacketSource.Packet(
        new PacketSource.Format("test", new byte[0]),
        pts == null ? null : minus(pts, t(7, 90000)),
        pts,
        duration,
        t(1, 1000000000),
        t(2, 1000000000),
        disc,
        ByteBuffer.wrap(new byte[] {(byte) payload}));
  }

  static DecodedPacket decoded(PacketSource.Packet p, int frames) {
    return new DecodedPacket(
        p,
        new PcmFormat(48000, 2, PcmFormat.Encoding.S16LE, PcmFormat.Layout.STEREO),
        ByteBuffer.allocate(frames * 4));
  }

  interface F {
    void run() throws Exception;
  }

  static void fails(F f, String what) throws Exception {
    try {
      f.run();
      throw new AssertionError(what);
    } catch (IOException expected) {
      assertions++;
    }
  }

  @org.junit.jupiter.api.Test
  void reconstructsOnlyDeclaredQuantizedClocks() throws Exception {
    for (int rate : new int[] {32000, 44100, 48000}) {
      var clock = new QuantizedPcmClock(t(1, 90000));
      for (int i = 0; i < 1000; i++) {
        var ideal = plus(t(1, 90000), t(i * 1024L, rate));
        BigInteger numerator =
            BigInteger.valueOf(ideal.ticks()).multiply(BigInteger.valueOf(90000));
        long ticks =
            numerator
                .add(BigInteger.valueOf(ideal.timescale() / 2))
                .divide(BigInteger.valueOf(ideal.timescale()))
                .longValueExact();
        var original = p(t(ticks, 90000), t(1024, rate), i == 400 || i == 800, i);
        var mapped = clock.map(original);
        yes(mapped.presentationTime().compareValue(ideal) == 0, "TS no cumulative packet rounding");
        yes(
            minus(mapped.presentationTime(), mapped.decodeTime())
                    .compareValue(minus(original.presentationTime(), original.decodeTime()))
                == 0,
            "DTS shift preserves decode relation");
        yes(
            mapped.discardStart().equals(original.discardStart())
                && mapped.discardEnd().equals(original.discardEnd()),
            "TS does not round discard");
        yes(mapped.duration().equals(original.duration()), "TS exact duration unchanged");
      }
    }
    var shortClock = new QuantizedPcmClock(t(1, 1000));
    var shortPacket = p(t(0, 1), t(1, 48000), false, 1);
    shortClock.map(shortPacket);
    fails(() -> shortClock.map(shortPacket), "same short packet mapped twice");
    var longClock = new QuantizedPcmClock(t(1, 1000));
    longClock.map(p(t(0, 1), t(1, 50), false, 1));
    fails(
        () -> longClock.map(p(t(0, 1), t(1, 50), false, 1)),
        "new duplicate long packet hidden as jitter");
    var backward = new QuantizedPcmClock(t(1, 1000));
    backward.map(p(t(0, 1), t(1, 48000), false, 1));
    fails(
        () -> backward.map(p(t(-1, 10000), t(1, 48000), false, 2)),
        "backwards short packet hidden as jitter");
    var coarse = new QuantizedPcmClock(t(1, 1000));
    for (int i = 0; i < 11; i++) coarse.map(p(t(0, 1), t(1, 10000), false, i));
    fails(
        () -> coarse.map(p(t(0, 1), t(1, 10000), false, 99)),
        "unbounded same coarse timestamp packet stream");
    var gap = QuantizedPcmClock.matroska(t(1, 1000));
    gap.map(decoded(p(t(0, 1), null, false, 1), 1024));
    var after = gap.map(decoded(p(t(23, 1000), null, false, 2), 1024));
    yes(
        after.source().presentationTime().compareValue(t(23, 1000)) == 0,
        "true gap greater than tick survives");
    var mkv = QuantizedPcmClock.matroska(t(1, 1000));
    var raw = p(t(-21333333, 1000000000), t(21, 1000), false, 1);
    var rawDecoded = decoded(raw, 1024);
    var normalized = mkv.map(rawDecoded);
    yes(
        normalized.source().presentationTime().compareValue(t(-1024, 48000)) == 0,
        "negative AAC delay nearest sample exactly once");
    yes(
        normalized.source().duration().compareValue(t(1024, 48000)) == 0,
        "rough block duration uses codec extent");
    yes(raw.presentationTime().equals(t(-21333333, 1000000000)), "raw metadata remains immutable");
    yes(rawDecoded.pcm().remaining() == 4096, "raw PCM remains complete");
    var padding = QuantizedPcmClock.matroska(t(1, 1000));
    var paddingPacket =
        new PacketSource.Packet(
            raw.format(),
            null,
            t(0, 1),
            null,
            t(20834, 1000000000),
            t(20834, 1000000000),
            false,
            ByteBuffer.allocate(0));
    var rounded = padding.map(decoded(paddingPacket, 1024));
    yes(
        rounded.source().discardStart().compareValue(t(1, 48000)) == 0
            && rounded.source().discardEnd().compareValue(t(1, 48000)) == 0,
        "ns padding nearest not ceil2");
    var lace = QuantizedPcmClock.matroska(t(1, 1000));
    lace.map(decoded(p(t(0, 1), null, false, 1), 576));
    var laced = lace.map(decoded(p(null, null, false, 2), 1024));
    yes(
        laced.source().presentationTime().compareValue(t(576, 48000)) == 0,
        "known lace clock from actual decoded frames");
    lace.reset();
    fails(() -> lace.map(decoded(p(null, null, false, 3), 1024)), "unknown first lace after reset");
    var noLace = new QuantizedPcmClock(t(1, 90000));
    fails(
        () -> noLace.map(decoded(p(null, null, false, 1), 1024)), "non-MKV cannot invent null PTS");
    var clip = QuantizedPcmClock.matroska(t(1, 1000));
    var clipped = clip.map(decoded(p(t(0, 1), t(1, 100), false, 1), 1024));
    yes(
        clipped.source().duration().compareValue(t(480, 48000)) == 0,
        "true container clipping preserved");
    var stage = new QuantizedPcmClock(t(1, 90000));
    stage.map(p(t(1, 1), t(1, 50), false, 1));
    fails(
        () -> stage.map(decoded(p(t(102, 100), t(1, 50), false, 2), 960)),
        "no compressed+decoded double map");
    stage.reset();
    yes(
        stage.map(p(t(0, 1), t(1, 50), true, 1)).presentationTime().ticks() == 0,
        "explicit reset permits actual epoch restart");
    fails(
        () -> new QuantizedPcmClock(t(1, 90000)).map(p(null, t(1, 50), false, 1)),
        "missing TS PTS");
    fails(
        () -> new QuantizedPcmClock(t(1, 90000)).map(p(t(0, 1), null, false, 1)),
        "unknown TS duration");
    fails(
        () -> new QuantizedPcmClock(t(1, 90000)).map(p(t(0, 1), t(0, 1), false, 1)),
        "zero TS duration");
    System.out.println("QuantizedPcmClock assertions=" + assertions + " PASS");
  }
}
