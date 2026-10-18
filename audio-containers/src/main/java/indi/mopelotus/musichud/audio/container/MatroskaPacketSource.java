package indi.mopelotus.musichud.audio.container;

import indi.mopelotus.musichud.audio.container.matroska.*;
import indi.mopelotus.musichud.audio.container.matroska.format.*;
import indi.mopelotus.musichud.audio.container.io.SeekableInputStream;
import indi.mopelotus.musichud.audio.container.info.AudioTrackInfoProvider;
import indi.mopelotus.musichud.audio.codec.PacketSource;
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
      Time duration = null;
      double ticks = file.getDuration();
      if (Double.isFinite(ticks)
          && ticks >= 0
          && ticks == Math.rint(ticks)
          && ticks < Long.MAX_VALUE)
        duration =
            new Time(Math.multiplyExact((long) ticks, file.getTimecodeScale()), 1_000_000_000);
      info =
          new Track(
              format,
              duration,
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
