package indi.mopelotus.musichud.audio.codec;

import indi.mopelotus.musichud.audio.codec.internal.Mp3FrameIndex;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Immutable sparse frame index for an unchanged MP3 source. Building reads the complete source
 * once, without PCM decoding. The caller binds this index to its source snapshot and owns caching
 * and HTTP range identity; the index never fetches or retains a transport.
 */
public final class Mp3SeekIndex {
  public record Point(long byteOffset, long frameIndex, long payloadPrefix) {}

  /**
   * Byte offset to reopen, bounded preroll and the desired audible sample in the source timeline.
   */
  public record Plan(
      long byteOffset,
      long encodedFrameIndex,
      long desiredRawSample,
      long audibleSample,
      long estimatedMaxFramesToTarget) {}

  private final Mp3FrameIndex core;
  private final List<Point> points;

  private Mp3SeekIndex(Mp3FrameIndex core) {
    this.core = core;
    points =
        core.points().stream()
            .map(p -> new Point(p.byteOffset(), p.frameIndex(), p.payloadPrefix()))
            .toList();
  }

  /**
   * Owns and closes input on completion, failure or cancellation. Close its transport to unblock
   * reads.
   */
  public static Mp3SeekIndex build(InputStream owned, BooleanSupplier cancelled)
      throws IOException {
    return new Mp3SeekIndex(Mp3FrameIndex.build(owned, cancelled));
  }

  public int sampleRate() {
    return core.metadata().header().sampleRate();
  }

  public int channels() {
    return core.metadata().header().channels();
  }

  public long encodedFrames() {
    return core.encodedFrames();
  }

  public long audibleFrames() {
    return core.audibleFrames();
  }

  public long rawStartSample() {
    return core.rawStartSample();
  }

  public long scanBytes() {
    return core.scanBytes();
  }

  public long primitiveIndexBytes() {
    return core.primitiveIndexBytes();
  }

  public List<Point> points() {
    return points;
  }

  public Plan plan(long audibleSample) {
    var p = core.plan(audibleSample);
    return new Plan(
        p.byteOffset(),
        p.encodedFrameIndex(),
        p.desiredRawSample(),
        p.audibleSample(),
        p.estimatedMaxFramesToTarget());
  }

  Mp3FrameIndex core() {
    return core;
  }

  static Mp3FrameIndex.Plan corePlan(Plan p) {
    Objects.requireNonNull(p, "plan");
    return new Mp3FrameIndex.Plan(
        p.byteOffset(),
        p.encodedFrameIndex(),
        p.desiredRawSample(),
        p.audibleSample(),
        p.estimatedMaxFramesToTarget());
  }
}
