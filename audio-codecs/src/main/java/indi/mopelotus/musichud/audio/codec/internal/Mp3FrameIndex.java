package indi.mopelotus.musichud.audio.codec.internal;

import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Immutable compressed-frame index. One full source scan; sparse exact frame offsets, no Xing TOC
 * timing assumption.
 */
public final class Mp3FrameIndex {
  public static final int STRIDE = 32;

  public record Point(long byteOffset, long frameIndex, long payloadPrefix) {}

  public record Plan(
      long byteOffset,
      long encodedFrameIndex,
      long desiredRawSample,
      long audibleSample,
      long estimatedMaxFramesToTarget) {}

  private final List<Point> points;
  private final Mp3Headers.Metadata metadata;
  private final long frames, rawStart, audibleLength, scanBytes;

  private Mp3FrameIndex(
      List<Point> points, Mp3Headers.Metadata metadata, long frames, long scanBytes)
      throws IOException {
    this.points = List.copyOf(points);
    this.metadata = metadata;
    this.frames = frames;
    this.scanBytes = scanBytes;
    var h = metadata.header();
    boolean info = metadata.vbrKind().equals("Xing") || metadata.vbrKind().equals("Info");
    boolean gapless =
        metadata.declaredFrames() > 0
            && metadata.encoderDelay() >= 0
            && metadata.encoderPadding() >= 0;
    rawStart = (info ? h.samplesPerFrame() : 0) + (gapless ? metadata.encoderDelay() + 529 : 0);
    audibleLength =
        gapless
            ? metadata.declaredFrames() * h.samplesPerFrame()
                - metadata.encoderDelay()
                - metadata.encoderPadding()
            : frames * h.samplesPerFrame() - rawStart;
    if (audibleLength < 0 || rawStart + audibleLength > frames * h.samplesPerFrame())
      throw new IOException("Inconsistent gapless MPEG frame declaration");
    if (gapless && metadata.declaredFrames() + (info ? 1 : 0) != frames)
      throw new EOFException("Physical MPEG frame count differs from Xing declaration");
  }

  public Mp3Headers.Metadata metadata() {
    return metadata;
  }

  public long encodedFrames() {
    return frames;
  }

  public long audibleFrames() {
    return audibleLength;
  }

  public long rawStartSample() {
    return rawStart;
  }

  public long scanBytes() {
    return scanBytes;
  }

  public List<Point> points() {
    return points;
  }

  public long primitiveIndexBytes() {
    return points.size() * 24L;
  }

  public Plan plan(long audibleSample) {
    if (audibleSample < 0 || audibleSample > audibleLength)
      throw new IllegalArgumentException("Seek outside audible timeline");
    var h = metadata.header();
    long raw = rawStart + audibleSample;
    long t = Math.min(frames - 1, raw / h.samplesPerFrame());
    long warmFrames = (2048L + h.samplesPerFrame() - 1) / h.samplesPerFrame() + 2;
    long q = Math.max(0, t - warmFrames);
    int warmPoint = (int) (q / STRIDE);
    long required = points.get(warmPoint).payloadPrefix() - (h.version().equals("1") ? 511 : 255);
    int selected = warmPoint;
    while (selected > 0 && points.get(selected).payloadPrefix() > required) selected--;
    Point p = points.get(selected);
    return new Plan(p.byteOffset(), p.frameIndex(), raw, audibleSample, t - p.frameIndex() + 1);
  }

  /**
   * Owned input closes on success, cancellation, malformed input, or failure. Caller may also close
   * its transport to unblock cancellation.
   */
  public static Mp3FrameIndex build(InputStream owned, BooleanSupplier cancelled)
      throws IOException {
    Objects.requireNonNull(owned);
    Objects.requireNonNull(cancelled);
    try (PushbackInputStream in = new PushbackInputStream(new ProgressInputStream(owned), 10)) {
      long offset = Mp3Headers.skipTags(in), frames = 0, payload = 0;
      Mp3Headers.Metadata metadata = null;
      Mp3Headers.Header format = null;
      List<Point> points = new ArrayList<>();
      byte[] frame = new byte[8192];
      while (true) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new InterruptedIOException("MPEG index generation cancelled");
        byte[] head = in.readNBytes(4);
        if (head.length == 0) break;
        if (head.length == 4 && head[0] == 'T' && head[1] == 'A' && head[2] == 'G') {
          in.skipNBytes(124);
          offset += 128;
          continue;
        }
        if (head.length == 4 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
          in.unread(head);
          offset += Mp3Headers.skipTags(in);
          continue;
        }
        if (head.length != 4) throw new EOFException("Partial MPEG frame header");
        Mp3Headers.Header h = Mp3Headers.parse(head, 0);
        if (h == null) throw new IOException("Unrecognized MPEG frame/footer at " + offset);
        if (format != null
            && (!format.version().equals(h.version())
                || format.sampleRate() != h.sampleRate()
                || format.channels() != h.channels()))
          throw new IOException("MPEG format changed while indexing");
        if (h.frameBytes() > frame.length) throw new IOException("MPEG frame exceeds bound");
        System.arraycopy(head, 0, frame, 0, 4);
        if (in.readNBytes(frame, 4, h.frameBytes() - 4) != h.frameBytes() - 4)
          throw new EOFException("Truncated MPEG frame");
        if (metadata == null) {
          format = h;
          metadata = Mp3Headers.inspect(Arrays.copyOf(frame, h.frameBytes()), h, offset);
        }
        if (frames % STRIDE == 0) points.add(new Point(offset, frames, payload));
        int side =
            h.version().equals("1") ? (h.channels() == 1 ? 17 : 32) : (h.channels() == 1 ? 9 : 17);
        int data = h.frameBytes() - 4 - (h.crc() ? 2 : 0) - side;
        if (data < 0) throw new IOException("MPEG side information exceeds frame");
        payload += data;
        offset += h.frameBytes();
        frames++;
      }
      if (metadata == null || frames == 0) throw new EOFException("No MPEG frames to index");
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("MPEG index generation cancelled before completion");
      return new Mp3FrameIndex(points, metadata, frames, offset);
    }
  }

  private static final class ProgressInputStream extends FilterInputStream {
    ProgressInputStream(InputStream in) {
      super(in);
    }

    public int read(byte[] b, int o, int l) throws IOException {
      if (l == 0) return 0;
      for (int i = 0; i < 32; i++) {
        int n = in.read(b, o, l);
        if (n != 0) return n;
      }
      throw new IOException("Index input made no progress");
    }
  }
}
