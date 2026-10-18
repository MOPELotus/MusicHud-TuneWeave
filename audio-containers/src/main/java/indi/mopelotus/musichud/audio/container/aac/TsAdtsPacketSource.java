package indi.mopelotus.musichud.audio.container.aac;

import indi.mopelotus.musichud.audio.container.ts.TsPacketSource;
import java.io.*;
import java.util.*;

/** Converts bounded TS AAC PES into complete raw AAC packets, retaining exact PTS anchors. */
public final class TsAdtsPacketSource extends AdtsPacketSource {
  public record Pes(
      byte[] elementary, long pts90k, boolean discontinuity, int streamType, long timelineEpoch) {
    public Pes(byte[] elementary, long pts90k, boolean discontinuity, int streamType) {
      this(elementary, pts90k, discontinuity, streamType, 0);
    }

    public Pes {
      Objects.requireNonNull(elementary);
      if (elementary.length > 65535) throw new IllegalArgumentException("PES exceeds bound");
      elementary = elementary.clone();
    }

    public byte[] elementary() {
      return elementary.clone();
    }
  }

  public interface PesInput extends Closeable {
    Pes next() throws IOException;
  }

  public TsAdtsPacketSource(PesInput input) throws IOException {
    super(new PesFeed(input));
  }

  public TsAdtsPacketSource(TsPacketSource input) throws IOException {
    this(
        new PesInput() {
          public Pes next() throws IOException {
            var p = input.next();
            return p == null
                ? null
                : new Pes(p.data(), p.pts90k(), p.discontinuity(), p.streamType());
          }

          public void close() throws IOException {
            input.close();
          }
        });
  }

  /** Epoch attached to the most recent successfully returned AAC packet, never read-ahead state. */
  public long timelineEpoch() {
    return returnedTimelineEpoch();
  }

  private static final class PesFeed implements Feed {
    private final PesInput input;
    private byte[] current = new byte[0];
    private int pos;
    private long offset, serial, previousEpoch;
    private final ArrayDeque<Anchor> anchors = new ArrayDeque<>();
    private Anchor latest;

    PesFeed(PesInput input) {
      this.input = Objects.requireNonNull(input);
    }

    public int read() throws IOException {
      int empty = 0;
      while (pos == current.length) {
        Pes p = input.next();
        if (p == null) return -1;
        if (p.streamType() != 15)
          throw new IOException("TS AAC LATM is not ADTS and is unsupported by this source");
        current = p.elementary();
        pos = 0;
        if (current.length == 0 && ++empty > 1024)
          throw new IOException("Too many empty audio PES packets");
        if (p.pts90k() != Long.MIN_VALUE || p.discontinuity() || p.timelineEpoch() != previousEpoch)
          anchors.addLast(
              new Anchor(
                  offset,
                  ++serial,
                  p.pts90k() == Long.MIN_VALUE ? null : new Time(p.pts90k(), 90000),
                  p.discontinuity(),
                  p.timelineEpoch()));
        previousEpoch = p.timelineEpoch();
        if (anchors.size() > 8192)
          throw new IOException("Too many PES timestamps inside one ADTS frame");
      }
      offset++;
      return current[pos++] & 255;
    }

    public long position() {
      return offset;
    }

    public Anchor anchor(long frameOffset) throws IOException {
      for (Anchor a : anchors)
        if (a.byteOffset() > frameOffset && a.discontinuity())
          throw new IOException("ADTS frame crosses MPEG discontinuity");
      while (!anchors.isEmpty() && anchors.getFirst().byteOffset() <= frameOffset)
        latest = anchors.removeFirst();
      return latest;
    }

    public void close() throws IOException {
      input.close();
    }
  }
}
