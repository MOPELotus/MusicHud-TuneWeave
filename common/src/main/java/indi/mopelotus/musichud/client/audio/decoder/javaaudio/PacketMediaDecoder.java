package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.*;

/** Raw media-clock stream with real timestamp-gap silence; MP4 edits remain outside this layer. */
public final class PacketMediaDecoder implements PcmDecoder {
  private final PacketSource source;
  private final PacketPcmTimeline timeline = new PacketPcmTimeline();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object reader = new Object();
  private PacketDecoder codec;
  private PacketSource.Format codecFormat;
  private PcmFormat format;
  private ByteBuffer pcm = ByteBuffer.allocate(0);
  private long silence, position;
  private boolean eof;

  public PacketMediaDecoder(PacketSource owned) throws IOException {
    source = owned;
    try {
      load();
      if (format == null) throw new IOException("Empty packet audio");
    } catch (Throwable error) {
      try {
        close();
      } catch (IOException e) {
        error.addSuppressed(e);
      }
      throw error;
    }
  }

  private PacketDecoder create(PacketSource.Format f) throws IOException {
    return switch (f.codec()) {
      case "mp4a" -> PacketCodecs.aac(f);
      case "alac" -> PacketCodecs.alac(f);
      default -> throw new IOException("Unsupported packet codec " + f.codec());
    };
  }

  private void load() throws IOException {
    while (!eof && silence == 0 && !pcm.hasRemaining()) {
      if (closed.get()) throw new IOException("Packet decoder closed");
      var packet = source.next();
      if (packet == null) {
        eof = true;
        if (codec != null) codec.finish();
        return;
      }
      if (codec == null || !packet.format().equals(codecFormat) || packet.discontinuity()) {
        if (codec != null) codec.close();
        codecFormat = packet.format();
        codec = create(codecFormat);
      }
      var result = codec.decode(packet);
      if (result == null) continue;
      var f = result.format();
      PcmFormat next =
          new PcmFormat(
              f.sampleRate(),
              f.channels(),
              PcmFormat.Encoding.valueOf(f.encoding().name()),
              PcmFormat.Layout.valueOf(f.layout().name()));
      if (format != null && !format.equals(next)) throw new IOException("Packet PCM format change");
      format = next;
      var span = timeline.accept(result);
      silence = span.silenceFrames();
      pcm = span.pcm();
    }
  }

  public PacketSource.Track track() {
    return source.track();
  }

  public PcmFormat format() {
    return format;
  }

  public long framePosition() {
    return position;
  }

  public int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    java.util.Objects.checkFromIndexSize(
        offset, Math.multiplyExact(maxFrames, format.frameBytes()), target.length);
    if (maxFrames == 0) return 0;
    synchronized (reader) {
      if (closed.get()) throw new IOException("Packet decoder closed");
      load();
      if (silence == 0 && !pcm.hasRemaining() && eof) return -1;
      int count;
      if (silence > 0) {
        count = (int) Math.min(maxFrames, silence);
        java.util.Arrays.fill(target, offset, offset + count * format.frameBytes(), (byte) 0);
        silence -= count;
      } else {
        count = Math.min(maxFrames, pcm.remaining() / format.frameBytes());
        pcm.get(target, offset, count * format.frameBytes());
      }
      position = Math.addExact(position, count);
      return count;
    }
  }

  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) return;
    IOException error = null;
    try {
      source.close();
    } catch (IOException e) {
      error = e;
    }
    synchronized (reader) {
      if (codec != null)
        try {
          codec.close();
        } catch (IOException e) {
          if (error == null) error = e;
          else error.addSuppressed(e);
        }
    }
    if (error != null) throw error;
  }
}
