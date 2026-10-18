package indi.mopelotus.musichud.audio.container;

import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.container.info.AudioTrackInfoProvider;
import indi.mopelotus.musichud.audio.container.io.SeekableInputStream;
import indi.mopelotus.musichud.audio.container.matroska.*;
import indi.mopelotus.musichud.audio.container.matroska.format.*;
import java.io.*;
import java.math.BigInteger;
import java.util.*;

/** Exact packet metadata on a synchronous pull cursor; no decoder thread or queue. */
public final class MatroskaPacketSource implements PacketSource {
  private final OwnedInput input;
  private final MatroskaStreamingFile file;
  private final MatroskaStreamingFile.PacketCursor cursor;
  private final MatroskaFileTrack selected;
  private final Format format;
  private final Track info;
  private final Time estimatedDuration;
  private Packet pending;
  private volatile boolean closed;
  private boolean discontinuity;

  /** Takes ownership on entry, including an invalid header. */
  public MatroskaPacketSource(SeekableInputStream owned) throws IOException {
    input = new OwnedInput(Objects.requireNonNull(owned, "owned"));
    try {
      file = new MatroskaStreamingFile(input);
      file.readFile();
      selected =
          Arrays.stream(file.getTrackList())
              .filter(t -> t.type == MatroskaFileTrack.Type.AUDIO)
              .findFirst()
              .orElseThrow(() -> new IOException("No audio track"));
      format =
          new Format(
              codecName(selected.codecId),
              selected.codecPrivate == null ? new byte[0] : selected.codecPrivate);
      // Info.Duration is a muxer estimate on the segment clock, not a decoded sample endpoint.
      // Keep it available to UI/probing without advertising an exact mediaDuration to playback.
      double durationNs = file.getDuration() * file.getTimecodeScale();
      estimatedDuration =
          Double.isFinite(durationNs) && durationNs >= 0 && durationNs < Long.MAX_VALUE
              ? new Time(Math.round(durationNs), 1_000_000_000)
              : null;
      info =
          new Track(
              format,
              null,
              List.of(),
              new Time(selected.codecDelayNs, 1_000_000_000),
              new Time(selected.seekPreRollNs, 1_000_000_000));
      cursor = file.packetCursor(selected.index);
    } catch (IOException | RuntimeException | AssertionError e) {
      try {
        input.close();
      } catch (IOException x) {
        e.addSuppressed(x);
      }
      if (e instanceof IOException x) throw x;
      throw new IOException("Invalid Matroska", e);
    }
  }

  private static String codecName(String containerId) {
    return switch (containerId) {
      case "A_AAC" -> "mp4a";
      case "A_OPUS" -> "opus";
      case "A_VORBIS" -> "vorbis";
      default -> containerId;
    };
  }

  /** The exact quantum of stored Cluster/Block timestamps, before codec-delay subtraction. */
  public Time timestampQuantum() {
    return new Time(file.getTimecodeScale(), 1_000_000_000);
  }

  /**
   * Approximate Info.Duration for display/probing, rounded to nanoseconds; never an exact PCM end.
   * Packet BlockDuration also uses the segment clock. DefaultDuration, when present, uses native
   * nanoseconds instead. Neither inferred packet duration may override the decoder's sample count.
   */
  public Time estimatedDuration() {
    return estimatedDuration;
  }

  /**
   * Packet PTS already subtracts Matroska CodecDelay from the stored timestamp. Track.codecDelay
   * describes codec priming; consumers must not subtract it again as a timestamp translation.
   * Negative leading PTS and packet discard padding still require presentation trimming once.
   */
  public Track track() {
    return info;
  }

  public Packet next() throws IOException {
    if (closed) throw new IOException("Source closed");
    if (pending != null) {
      var p = pending;
      pending = null;
      return p;
    }
    try {
      var packet = cursor.next();
      if (packet == null) return null;
      var t = packet.timing();
      Time pts =
          t.laceIndex() == 0 || selected.defaultDurationNs > 0
              ? new Time(t.presentationTimestampNs(), 1_000_000_000)
              : null;
      Time duration =
          selected.defaultDurationNs > 0
              ? new Time(selected.defaultDurationNs, 1_000_000_000)
              : t.laceCount() == 1 && t.blockDurationNs() > 0
                  ? new Time(t.blockDurationNs(), 1_000_000_000)
                  : null;
      Time start =
          t.discardPaddingNs() < 0
              ? new Time(Math.negateExact(t.discardPaddingNs()), 1_000_000_000)
              : Time.ZERO;
      Time end =
          t.discardPaddingNs() > 0 ? new Time(t.discardPaddingNs(), 1_000_000_000) : Time.ZERO;
      var result =
          new Packet(format, null, pts, duration, start, end, discontinuity, packet.encoded());
      if (closed) throw new IOException("Source closed during read");
      discontinuity = false;
      return result;
    } catch (RuntimeException e) {
      throw new IOException("Invalid Matroska packet", e);
    }
  }

  public Time seekMedia(Time target) throws IOException {
    if (closed) throw new IOException("Source closed");
    if (target == null || target.ticks() < 0) throw new IOException("Invalid seek");
    long ns;
    try {
      ns =
          BigInteger.valueOf(target.ticks())
              .multiply(BigInteger.valueOf(1_000_000_000))
              .divide(BigInteger.valueOf(target.timescale()))
              .longValueExact();
    } catch (ArithmeticException e) {
      throw new IOException("Seek overflow", e);
    }
    pending = null;
    cursor.seekPresentationNs(ns);
    discontinuity = true;
    pending = next();
    return pending == null ? null : pending.presentationTime();
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    input.close();
  }

  private static final class OwnedInput extends SeekableInputStream {
    final SeekableInputStream delegate;
    volatile boolean closed;

    OwnedInput(SeekableInputStream d) {
      super(d.getContentLength(), 0);
      delegate = d;
    }

    public long getPosition() {
      return delegate.getPosition();
    }

    protected void seekHard(long at) throws IOException {
      if (closed) throw new IOException("Input closed");
      delegate.seek(at);
    }

    public boolean canSeekHard() {
      return delegate.canSeekHard();
    }

    public List<AudioTrackInfoProvider> getTrackInfoProviders() {
      return delegate.getTrackInfoProviders();
    }

    public int read() throws IOException {
      if (closed) throw new IOException("Input closed");
      return delegate.read();
    }

    public int read(byte[] b, int o, int n) throws IOException {
      if (closed) throw new IOException("Input closed");
      return delegate.read(b, o, n);
    }

    @Override
    public synchronized void close() throws IOException {
      if (closed) return;
      closed = true;
      delegate.close();
    }
  }
}
