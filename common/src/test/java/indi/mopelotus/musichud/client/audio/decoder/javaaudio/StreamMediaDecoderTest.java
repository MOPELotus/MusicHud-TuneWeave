package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class StreamMediaDecoderTest {
  @org.junit.jupiter.api.Test
  void chainedPresentation() throws Exception {
    chain();
  }

  @org.junit.jupiter.api.Test
  void seekSourceAndTargetGrids() throws Exception {
    seek();
  }

  @org.junit.jupiter.api.Test
  void pendingLinkCancellation() throws Exception {
    for (int i = 0; i < 10; i++) {
      cancellation();
      blockedReadCancellation();
    }
  }

  @org.junit.jupiter.api.Test
  void emptyLayoutsAndOwnership() throws Exception {
    boundaries();
  }

  static int assertions;

  static void check(boolean ok, String why) {
    assertions++;
    if (!ok) throw new AssertionError(why);
  }

  static class Core implements PcmDecoder {
    PcmFormat f;
    final int frames, chunk;
    int pos;
    boolean closed;

    Core(int rate, int ch, int frames, int chunk) {
      f =
          new PcmFormat(
              rate,
              ch,
              PcmFormat.Encoding.F32LE,
              ch == 1
                  ? PcmFormat.Layout.MONO
                  : ch == 2 ? PcmFormat.Layout.STEREO : PcmFormat.Layout.UNSPECIFIED);
      this.frames = frames;
      this.chunk = chunk;
    }

    public PcmFormat format() {
      return f;
    }

    public long framePosition() {
      return pos;
    }

    public int readFrames(byte[] a, int off, int max) throws IOException {
      if (closed) throw new IOException("closed");
      if (pos == frames) return -1;
      int n = Math.min(Math.min(max, chunk), frames - pos);
      ByteBuffer b = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN);
      b.position(off);
      for (int j = 0; j < n; j++)
        for (int c = 0; c < f.channels(); c++) b.putFloat((float) Math.sin((pos + j) * .1) * .2f);
      pos += n;
      return n;
    }

    public void close() {
      closed = true;
    }
  }

  static class Links implements StreamMediaDecoder.Input {
    final List<PcmDecoder> cores;
    int next;
    boolean closed;

    Links(PcmDecoder... c) {
      cores = Arrays.asList(c);
    }

    public PcmDecoder nextLink() throws IOException {
      if (closed) throw new IOException("closed");
      return next == cores.size() ? null : cores.get(next++);
    }

    public void close() throws IOException {
      closed = true;
      for (var c : cores) c.close();
    }
  }

  static byte[] read(PcmDecoder d) throws IOException {
    var out = new ByteArrayOutputStream();
    byte[] b = new byte[37 * d.format().frameBytes()];
    int n;
    long start = d.framePosition();
    while ((n = d.readFrames(b, 0, 37)) >= 0) {
      check(n > 0 && n <= 37, "whole frames");
      out.write(b, 0, n * d.format().frameBytes());
      check(d.framePosition() == start + out.size() / d.format().frameBytes(), "global position");
    }
    return out.toByteArray();
  }

  static void chain() throws Exception {
    var a = new Core(44100, 2, 441, 1);
    var b = new Core(48000, 1, 480, 7);
    var links = new Links(a, b);
    try (var d = new StreamMediaDecoder(links)) {
      check(links.next == 1, "no prescan ctor");
      check(d.format().sampleRate() == 44100 && d.format().channels() == 2, "first fixed");
      byte[] first = new byte[8];
      check(d.readFrames(first, 0, 1) == 1, "first PCM");
      check(links.next == 1, "first PCM no next link");
      byte[] rest = read(d);
      check(rest.length / 8 + 1 == 882, "exact two links");
      check(d.framePosition() == 882, "final position");
    }
    check(a.closed && b.closed, "all closed");
    PcmDecoder[] many = new PcmDecoder[100];
    for (int i = 0; i < 100; i++)
      many[i] = new Core(i % 2 == 0 ? 44100 : 48000, i % 2 == 0 ? 2 : 1, 17, 1);
    try (var d = new StreamMediaDecoder(new Links(many))) {
      long expected = (long) Math.ceil(50 * 17.0 + 50 * 17 * 44100.0 / 48000);
      check(read(d).length / 8 == expected, "cumulative exact carry");
    }
  }

  static void seek() throws Exception {
    try (var d = new StreamMediaDecoder(new Links(new Core(44100, 2, 100, 3)), 44100, 1000, 1000)) {
      check(read(d).length / 8 == 100, "seek same-rate exact");
      check(d.framePosition() == 1100, "absolute seek position");
    }
    try (var d = new StreamMediaDecoder(new Links(new Core(48000, 1, 4800, 13)), 44100, 2205, 0)) {
      check(read(d).length / 8 == 2205, "preroll cropped target grid");
      check(d.framePosition() == 4410, "preroll end");
    }
  }

  static void cancellation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var late = new Core(44100, 2, 10, 1);
    var first = new Core(44100, 2, 1, 1);
    AtomicInteger calls = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    var input =
        new StreamMediaDecoder.Input() {
          public PcmDecoder nextLink() throws IOException {
            if (calls.getAndIncrement() == 0) return first;
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException e) {
              throw new IOException(e);
            }
            return late;
          }

          public void close() {
            release.countDown();
          }
        };
    var d = new StreamMediaDecoder(input);
    d.readFrames(new byte[8], 0, 1);
    Thread reader =
        new Thread(
            () -> {
              try {
                d.readFrames(new byte[8], 0, 1);
                failure.set(new AssertionError("late read accepted"));
              } catch (IOException expected) {
              }
            });
    reader.start();
    check(entered.await(2, TimeUnit.SECONDS), "blocking next link entered");
    d.close();
    reader.join(2000);
    check(!reader.isAlive(), "close unblocks next link");
    check(late.closed, "late link closed");
    check(failure.get() == null, "late rejected");
    d.close();
  }

  static void blockedReadCancellation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var calls = new AtomicInteger();
    var problem = new AtomicReference<Throwable>();
    var core =
        new Core(44100, 2, 100, 1) {
          @Override
          public int readFrames(byte[] a, int off, int max) throws IOException {
            if (calls.getAndIncrement() == 0) return super.readFrames(a, off, max);
            entered.countDown();
            try {
              release.await();
            } catch (InterruptedException invalid) {
              throw new IOException(invalid);
            }
            return 1; // Deliberately simulate a late successful callback after cancellation.
          }

          @Override
          public void close() {
            super.close();
            release.countDown();
          }
        };
    var decoder = new StreamMediaDecoder(new Links(core));
    decoder.readFrames(new byte[8], 0, 1);
    Thread reader =
        new Thread(
            () -> {
              try {
                decoder.readFrames(new byte[8], 0, 1);
                problem.set(new AssertionError("late PCM accepted"));
              } catch (IOException expected) {
              }
            });
    reader.start();
    check(entered.await(2, TimeUnit.SECONDS), "blocked PCM read entered");
    decoder.close();
    reader.join(2000);
    check(!reader.isAlive(), "close unblocks current PCM read");
    check(
        problem.get() == null && decoder.framePosition() == 1,
        "late PCM rejected without position advance");
  }

  static void boundaries() throws Exception {
    try (var d =
        new StreamMediaDecoder(new Links(new Core(44100, 2, 0, 1), new Core(48000, 1, 0, 1)))) {
      check(read(d).length == 0, "all empty EOF");
      check(d.framePosition() == 0, "empty position");
    }
    try (var d =
        new StreamMediaDecoder(new Links(new Core(44100, 2, 0, 1), new Core(48000, 1, 480, 3)))) {
      check(d.format().sampleRate() == 44100, "empty first fixes rate");
      check(read(d).length / 8 == 441, "empty then populated");
    }
    var surround = new Core(48000, 6, 480, 13);
    surround.f = new PcmFormat(48000, 6, PcmFormat.Encoding.F32LE, PcmFormat.Layout.VORBIS);
    try (var d = new StreamMediaDecoder(new Links(surround, new Core(48000, 2, 480, 13)))) {
      check(read(d).length / 8 == 960, "surround and stereo");
      check(d.hasDownmixedChannels(), "sticky source downmix");
    }
    var unknown = new Core(48000, 6, 480, 13);
    unknown.f = new PcmFormat(48000, 6, PcmFormat.Encoding.F32LE, PcmFormat.Layout.UNSPECIFIED);
    try (var d = new StreamMediaDecoder(new Links(unknown))) {
      try {
        read(d);
        throw new AssertionError("unknown layout accepted");
      } catch (IOException expected) {
        check(true, "unknown reject");
      }
    }
    var core = new Core(44100, 2, 100, 1);
    var closed = new AtomicBoolean();
    var bad =
        new StreamMediaDecoder.Input() {
          public PcmDecoder nextLink() {
            return core;
          }

          public void close() throws IOException {
            closed.set(true);
            throw new IOException("close failed");
          }
        };
    var d = new StreamMediaDecoder(bad);
    try {
      d.close();
      throw new AssertionError("missing close error");
    } catch (IOException expected) {
      check(core.closed && closed.get(), "cleanup despite input close failure");
    }
    d.close();
    var ctorCore = new Core(44100, 2, 100, 1);
    var ctorLinks = new Links(ctorCore);
    try {
      new StreamMediaDecoder(ctorLinks, 1, 0, 0);
      throw new AssertionError("bad rate accepted");
    } catch (IllegalArgumentException expected) {
      check(ctorLinks.closed && ctorCore.closed, "constructor error ownership");
    }
  }

  static void real(String[] args) throws Exception {
    if (args.length == 0) return;
    Links links =
        new Links(
            JavaCodecs.ogg(new FileInputStream(args[0])),
            JavaCodecs.ogg(new FileInputStream(args[1])));
    try (var d = new StreamMediaDecoder(links)) {
      byte[] pcm = read(d);
      check(pcm.length / 8 == 8820, "real differing-rate links");
      for (int i = 0; i < pcm.length; i += 4)
        check(
            Float.isFinite(ByteBuffer.wrap(pcm, i, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat()),
            "real finite");
      System.out.println("real frames=" + pcm.length / 8);
    }
  }

  public static void main(String[] args) throws Exception {
    chain();
    seek();
    for (int i = 0; i < 10; i++) {
      cancellation();
      blockedReadCancellation();
    }
    boundaries();
    real(args);
    System.out.println("assertions=" + assertions);
  }
}
