package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.audio.codec.*;
import java.io.*;
import java.nio.*;
import java.util.*;

class PacketPcmNormalizerTest {
  static int assertions;

  static void yes(boolean good, String why) {
    assertions++;
    if (!good) throw new AssertionError(why);
  }

  static final PacketSource.Format CODEC = new PacketSource.Format("synthetic", new byte[0]);

  static DecodedPacket packet(
      int rate,
      int channels,
      float[] floats,
      long pts,
      long scale,
      boolean disc,
      long durationFrames,
      long discardStart,
      long discardEnd) {
    var bytes = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
    for (float f : floats) bytes.putFloat(f);
    bytes.flip();
    var time = new PacketSource.Time(pts, scale);
    var packet =
        new PacketSource.Packet(
            CODEC,
            time,
            time,
            durationFrames < 0 ? null : new PacketSource.Time(durationFrames, rate),
            new PacketSource.Time(discardStart, rate),
            new PacketSource.Time(discardEnd, rate),
            disc,
            ByteBuffer.allocate(0));
    return new DecodedPacket(
        packet,
        new PcmFormat(
            rate,
            channels,
            PcmFormat.Encoding.F32LE,
            channels == 1 ? PcmFormat.Layout.MONO : PcmFormat.Layout.STEREO),
        bytes);
  }

  static float[] tone(int rate, int channels, int count) {
    float[] values = new float[count * channels];
    for (int i = 0; i < count; i++)
      for (int c = 0; c < channels; c++)
        values[i * channels + c] =
            (float) (0.25 * Math.sin(2 * Math.PI * (997 + c * 31) * i / rate));
    return values;
  }

  static final class Result {
    long first = Long.MIN_VALUE, next;
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

    void add(PacketPcmNormalizer.Chunk chunk) {
      if (first == Long.MIN_VALUE) {
        first = chunk.targetFrameStart();
        next = first;
      }
      yes(chunk.targetFrameStart() == next, "continuous target frame position");
      yes(chunk.frames() * 8 == chunk.pcm().remaining(), "known stereo F32 extent");
      byte[] b = new byte[chunk.pcm().remaining()];
      chunk.pcm().get(b);
      bytes.writeBytes(b);
      next += chunk.frames();
    }

    float[] floats() {
      var b = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
      float[] f = new float[b.remaining() / 4];
      for (int i = 0; i < f.length; i++) f[i] = b.getFloat();
      return f;
    }
  }

  static void drain(PacketPcmNormalizer n, Result r, int chunk) throws Exception {
    for (int i = 0; i < 1000000; i++) {
      var p = n.pull(chunk);
      if (p == null) return;
      r.add(p);
    }
    throw new AssertionError("pull stalled");
  }

  static Result run(int target, List<DecodedPacket> packets, boolean[] restart, int chunk)
      throws Exception {
    var result = new Result();
    try (var n = new PacketPcmNormalizer(target)) {
      for (int i = 0; i < packets.size(); i++) {
        yes(n.accept(packets.get(i), restart != null && restart[i]), "one packet accepted");
        if (!n.canAccept()) yes(!n.accept(packets.get(i)), "bounded one packet backpressure");
        drain(n, result, chunk);
      }
      n.finish();
      drain(n, result, chunk);
      yes(n.isFinished(), "normal EOF drained");
      yes(n.pull(chunk) == null, "EOF idempotent");
    }
    return result;
  }

  static float[] concat(float[]... arrays) {
    int length = 0;
    for (float[] a : arrays) length += a.length;
    float[] out = new float[length];
    int p = 0;
    for (float[] a : arrays) {
      System.arraycopy(a, 0, out, p, a.length);
      p += a.length;
    }
    return out;
  }

  @org.junit.jupiter.api.Test
  void presentationBoundaryChecks() throws Exception {
    float[] first = tone(44100, 2, 123), second = tone(44100, 2, 257);
    float[] gap = new float[37 * 2];
    var split =
        run(
            48000,
            List.of(
                packet(44100, 2, first, 90000, 90000, false, 123, 0, 0),
                packet(44100, 2, second, 44100 + 160, 44100, false, 257, 0, 0)),
            null,
            7);
    var joined =
        run(
            48000,
            List.of(packet(44100, 2, concat(first, gap, second), 1, 1, false, 417, 0, 0)),
            null,
            31);
    yes(split.first == 48000, "nonzero PTS not rebased");
    yes(split.next == joined.next, "gap exact endpoint");
    float[] a = split.floats(), b = joined.floats();
    yes(a.length == b.length, "gap advancing filter output length");
    double signal = 0, error = 0;
    for (int i = 0; i < a.length; i++) {
      signal += b[i] * b[i];
      double d = a[i] - b[i];
      error += d * d;
    }
    double snr = 10 * Math.log10(signal / Math.max(error, 1e-300));
    yes(snr > 70, "ordinary gap retained resampler phase SNR=" + snr);
    System.out.println("ordinary gap partition SNR=" + snr);
    var zeros =
        run(
            44100,
            List.of(
                packet(44100, 2, new float[] {.1f, .2f}, 1, 1, false, 1, 0, 0),
                packet(44100, 2, new float[] {.3f, .4f}, 44105, 44100, false, 1, 0, 0)),
            null,
            1);
    yes(zeros.first == 44100 && zeros.next == 44106, "real gap extent");
    float[] z = zeros.floats();
    yes(z.length == 12 && z[0] == .1f && z[10] == .3f, "gap preserves both audio edges");
    for (int i = 2; i < 10; i++) yes(z[i] == 0, "gap literal zero PCM");
    var discgap =
        run(
            44100,
            List.of(
                packet(44100, 2, new float[] {.1f, .2f}, 0, 1, false, 1, 0, 0),
                packet(44100, 2, new float[] {.3f, .4f}, 5, 44100, true, 1, 0, 0)),
            null,
            1);
    yes(discgap.next == 6, "config discontinuity forward gap preserved");
    float[] raw = new float[20];
    for (int i = 0; i < 10; i++) {
      raw[i * 2] = raw[i * 2 + 1] = i / 10f;
    }
    var clipped = run(44100, List.of(packet(44100, 2, raw, 0, 1, false, 8, 2, 1)), null, 1);
    yes(clipped.first == 2 && clipped.next == 7, "discard and duration exactly once");
    yes(clipped.floats()[0] == .2f && clipped.floats()[8] == .6f, "discard selects raw indices");
    var tiny = new ArrayList<DecodedPacket>();
    long ticks = 0;
    for (int i = 0; i < 70; i++) {
      tiny.add(packet(44100, 2, new float[] {.1f, .1f, .2f, .2f}, ticks, 4410000, false, 2, 0, 0));
      ticks += 249;
    }
    var tinyResult = run(44100, tiny, null, 1);
    yes(tinyResult.next == 174, "fractional gap residual carried across packets");
    var dynamic = new ArrayList<DecodedPacket>();
    long dynamicTicks = 0;
    for (int i = 0; i < 100; i++) {
      int rate = i % 2 == 0 ? 44100 : 48000, channels = i % 2 == 0 ? 2 : 1, frames = 17;
      dynamic.add(
          packet(
              rate,
              channels,
              tone(rate, channels, frames),
              dynamicTicks,
              7056000,
              i > 0,
              frames,
              0,
              0));
      dynamicTicks += frames * (7056000 / rate);
    }
    var varied = run(44100, dynamic, null, 3);
    long expected = (dynamicTicks * 44100 + 7055999) / 7056000;
    yes(varied.next == expected, "dynamic format cumulative exact duration");
    var restart =
        run(
            44100,
            List.of(
                packet(44100, 2, tone(44100, 2, 17), 7, 1, false, 17, 0, 0),
                packet(48000, 1, tone(48000, 1, 17), 0, 1, true, 17, 0, 0)),
            new boolean[] {false, true},
            1);
    yes(
        restart.first == 7 * 44100 && restart.next == 7 * 44100 + 33,
        "explicit timeline restart anchored prior exact end");
    try (var n = new PacketPcmNormalizer()) {
      var r = new Result();
      n.accept(packet(44100, 2, tone(44100, 2, 17), 1, 1, false, 17, 0, 0));
      drain(n, r, 10);
      try {
        n.accept(packet(44100, 2, tone(44100, 2, 17), 0, 1, true, 17, 0, 0));
        throw new AssertionError("implicit restart");
      } catch (IOException expectedError) {
        assertions++;
      }
    }
    try (var n = new PacketPcmNormalizer()) {
      n.accept(packet(44100, 1, new float[] {Float.NaN}, 0, 1, false, 1, 0, 0));
      try {
        n.pull(1);
        throw new AssertionError("NaN accepted");
      } catch (IOException expectedError) {
        assertions++;
      }
    }
    try (var n = new PacketPcmNormalizer()) {
      n.finish();
      yes(n.pull(1) == null && n.isFinished(), "empty EOF");
      n.close();
      n.close();
      try {
        n.pull(1);
        throw new AssertionError("closed read");
      } catch (IOException expectedError) {
        assertions++;
      }
    }

    try (var n = new PacketPcmNormalizer()) {
      var result = new Result();
      yes(
          n.accept(packet(44100, 2, tone(44100, 2, 1), -1, 44100, false, 1, 0, 0)),
          "negative media PTS accepted");
      drain(n, result, 1);
      n.finish();
      drain(n, result, 1);
      yes(result.first == -1 && result.next == 0, "negative raw media origin preserved");
    }
    var emptyDiscard =
        run(
            44100,
            List.of(
                packet(44100, 2, new float[] {.1f, .1f}, 0, 1, true, 1, 1, 0),
                packet(44100, 2, new float[] {.2f, .2f}, 1, 1, false, 1, 0, 0)),
            null,
            1);
    yes(
        emptyDiscard.first == 44100 && emptyDiscard.next == 44101,
        "all-discard packet does not invent origin");
    try (var n = new PacketPcmNormalizer()) {
      try {
        n.accept(packet(44100, 2, new float[] {0, 0}, Long.MAX_VALUE, 1, false, 1, 0, 0));
        throw new AssertionError("timestamp overflow accepted");
      } catch (IOException expectedError) {
        assertions++;
      }
    }

    var fractional = new ArrayList<DecodedPacket>();
    for (int i = 0; i < 100; i++) {
      var t = new PacketSource.Time(i, 90000);
      var metadata =
          new PacketSource.Packet(
              CODEC,
              t,
              t,
              new PacketSource.Time(1, 90000),
              PacketSource.Time.ZERO,
              PacketSource.Time.ZERO,
              false,
              ByteBuffer.allocate(0));
      var sample =
          ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.1f).putFloat(.2f);
      sample.flip();
      fractional.add(
          new DecodedPacket(
              metadata,
              new PcmFormat(44100, 2, PcmFormat.Encoding.F32LE, PcmFormat.Layout.STEREO),
              sample));
    }
    var fractionalResult = run(44100, fractional, null, 1);
    yes(
        fractionalResult.next - fractionalResult.first == 49,
        "sub-source-frame duration windows carry selected frames");
    try (var n = new PacketPcmNormalizer()) {
      var r = new Result();
      var p = packet(44100, 2, new float[] {.1f, .2f}, 0, 1, false, 1, 0, 0);
      n.accept(p);
      drain(n, r, 1);
      try {
        n.accept(p);
        throw new AssertionError("duplicate one-frame window accepted");
      } catch (IOException expectedError) {
        assertions++;
      }
    }

    var fractionalGaps = new ArrayList<DecodedPacket>();
    for (int i = 0; i < 1000; i++) {
      var t = new PacketSource.Time(i * 2L, 90000);
      var metadata =
          new PacketSource.Packet(
              CODEC,
              t,
              t,
              new PacketSource.Time(1, 90000),
              PacketSource.Time.ZERO,
              PacketSource.Time.ZERO,
              false,
              ByteBuffer.allocate(0));
      var sample =
          ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(.1f).putFloat(.2f);
      sample.flip();
      fractionalGaps.add(
          new DecodedPacket(
              metadata,
              new PcmFormat(44100, 2, PcmFormat.Encoding.F32LE, PcmFormat.Layout.STEREO),
              sample));
    }
    var fg = run(48000, fractionalGaps, null, 7);
    long ideal = (1999L * 48000 + 89999) / 90000;
    yes(
        Math.abs(fg.next - ideal) <= 2,
        "1000 fractional duration+gap windows do not accumulate clock drift: "
            + fg.next
            + " vs "
            + ideal);

    for (int target : new int[] {7350, 384000})
      for (int rate : new int[] {7350, 384000}) {
        int sourceFrames = 17;
        var edge =
            run(
                target,
                List.of(
                    packet(rate, 2, tone(rate, 2, sourceFrames), 0, 1, false, sourceFrames, 0, 0)),
                null,
                7);
        long expectedEdge = (sourceFrames * (long) target + rate - 1) / rate;
        yes(edge.next == expectedEdge, "extreme target/source rate " + target + "/" + rate);
      }

    System.out.println("pipeline assertions=" + assertions + " PASS");
  }
}
