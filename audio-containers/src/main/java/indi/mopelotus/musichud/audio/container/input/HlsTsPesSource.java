package indi.mopelotus.musichud.audio.container.input;

import indi.mopelotus.musichud.audio.container.ts.TsPacketSource;
import java.io.*;
import java.util.*;

/**
 * Joins continuous TS segments before demuxing. Does not translate MPEG timestamps into PCM time.
 */
public final class HlsTsPesSource implements AutoCloseable {
  public record Packet(
      byte[] elementary,
      long pts90k,
      boolean discontinuity,
      long hlsDiscontinuitySequence,
      int streamType) {
    public Packet {
      elementary = elementary.clone();
    }

    public byte[] elementary() {
      return elementary.clone();
    }
  }

  private final HlsPlaybackInput input;
  private TsPacketSource demux;
  private HlsPlaybackInput.SegmentData pending;
  private SegmentChain chain;
  private volatile boolean closed;

  public HlsTsPesSource(HlsPlaybackInput input) {
    this.input = Objects.requireNonNull(input);
  }

  public Packet next() throws IOException {
    check();
    for (; ; ) {
      if (demux == null) {
        var first = pending == null ? input.next() : pending;
        pending = null;
        if (first == null) return null;
        checkTs(first);
        chain = new SegmentChain(first);
        demux = new TsPacketSource(chain);
      }
      var packet = demux.next();
      check();
      if (packet != null)
        return new Packet(
            packet.data(),
            packet.pts90k(),
            packet.discontinuity(),
            chain.epoch,
            packet.streamType());
      demux.close();
      demux = null;
      chain = null;
      if (pending == null) return null;
    }
  }

  private static void checkTs(HlsPlaybackInput.SegmentData segment) throws IOException {
    if (segment.initialization() != null)
      throw new IOException("HLS MAP requires fragmented-MP4 source, not TS");
  }

  private void check() throws IOException {
    if (closed) throw new IOException("HLS TS source closed");
  }

  private final class SegmentChain extends InputStream {
    private InputStream current;
    private final long epoch;
    private boolean eof;

    SegmentChain(HlsPlaybackInput.SegmentData first) {
      current = first.openMedia();
      epoch = first.discontinuitySequence();
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      int n = read(b, 0, 1);
      return n < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int off, int len) throws IOException {
      Objects.checkFromIndexSize(off, len, b.length);
      if (len == 0) return 0;
      check();
      if (eof) return -1;
      for (; ; ) {
        int n = current.read(b, off, len);
        if (n >= 0) return n;
        current.close();
        var next = input.next();
        check();
        if (next == null) {
          eof = true;
          return -1;
        }
        checkTs(next);
        if (next.segment().discontinuity() || next.discontinuitySequence() != epoch) {
          pending = next;
          eof = true;
          return -1;
        }
        current = next.openMedia();
      }
    }

    public void close() throws IOException {
      eof = true;
      current.close();
    }
  }

  /**
   * Input close cancels blocking fetch/reload; do not mutate demux concurrently with its reader.
   */
  @Override
  public void close() {
    closed = true;
    input.close();
  }
}
