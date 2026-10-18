package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.DecodedPacket;
import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.*;
import java.nio.*;

/**
 * Raw packet PCM to exact sparse media-clock spans. Presentation edits are a separate operation.
 */
public final class PacketPcmTimeline {
  /** Silence before the block retains timestamp gaps; samples never include discarded frames. */
  public record Span(long silenceFrames, long mediaFrame, ByteBuffer pcm, int frames) {
    public Span {
      pcm = pcm.asReadOnlyBuffer();
    }

    public ByteBuffer pcm() {
      return pcm.asReadOnlyBuffer();
    }
  }

  private PcmFormat format;
  private long nextFrame;

  public PcmFormat format() {
    return format;
  }

  public Span accept(DecodedPacket decoded) throws IOException {
    var f = decoded.format();
    if (format != null && !format.equals(f))
      throw new IOException("PCM format changed within presentation");
    format = f;
    var packet = decoded.source();
    PacketSource.Time pts = packet.presentationTime();
    if (pts == null) throw new IOException("Unknown packet presentation timestamp");
    long start = PresentationPlan.framesCeil(pts, f.sampleRate());
    int raw = decoded.rawFrames();
    long declared =
        packet.duration() == null
            ? raw
            : PresentationPlan.durationFrames(pts, packet.duration(), f.sampleRate());
    long from = PresentationPlan.framesCeil(packet.discardStart(), f.sampleRate());
    long to =
        Math.min(raw, declared) - PresentationPlan.framesCeil(packet.discardEnd(), f.sampleRate());
    if (from > to || from > raw || to < 0) throw new IOException("Packet discard exceeds raw PCM");
    start = Math.addExact(start, from);
    if (start < 0) {
      long drop = Math.min(-start, to - from);
      from += drop;
      start += drop;
    }
    int frames = Math.toIntExact(to - from);
    if (start < 0 && frames == 0) start = 0;
    if (start < nextFrame) throw new IOException("Overlapping packet presentation timestamp");
    long gap = start - nextFrame;
    ByteBuffer pcm = decoded.pcm();
    pcm.position(Math.toIntExact(from * f.frameBytes()));
    pcm.limit(Math.toIntExact(to * f.frameBytes()));
    nextFrame = Math.addExact(start, frames);
    return new Span(gap, start, pcm.slice(), frames);
  }
}
