package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.*;
import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import indi.mopelotus.musichud.client.audio.decoder.PacketPcmNormalizer;
import java.io.*;
import java.nio.*;
import java.util.concurrent.atomic.*;

/** Raw media-clock stream with real timestamp-gap silence; MP4 edits remain outside this layer. */
public final class PacketMediaDecoder implements PcmDecoder, PcmChannelConversion {
  @FunctionalInterface
  public interface Mapper {
    DecodedPacket map(DecodedPacket packet) throws IOException;
  }

  private final PacketSource source;
  private final Mapper mapper;
  private final int targetRate;
  private final AacPacketDecoder.Expectation aacRecovery;
  private AacPacketDecoder.Expectation aacExpectation;
  private PcmFormat initialSourceFormat;
  private PacketSource.Time firstDecodedTime;
  private PacketPcmNormalizer normalizer;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object reader = new Object();
  private PacketDecoder codec;
  private PacketSource.Format codecFormat;
  private PcmFormat format;
  private ByteBuffer pcm = ByteBuffer.allocate(0);
  private long silence, position;
  private boolean eof, pendingDiscontinuity;

  public PacketMediaDecoder(PacketSource owned) throws IOException {
    this(owned, 0);
  }

  public PacketMediaDecoder(PacketSource owned, long initialMediaFrame) throws IOException {
    this(owned, initialMediaFrame, packet -> packet);
  }

  public PacketMediaDecoder(PacketSource owned, long initialMediaFrame, Mapper mapper)
      throws IOException {
    this(owned, 0, initialMediaFrame, mapper);
  }

  public PacketMediaDecoder(
      PacketSource owned, int targetRate, long initialMediaFrame, Mapper mapper)
      throws IOException {
    this(owned, targetRate, initialMediaFrame, mapper, null);
  }

  public PacketMediaDecoder(
      PacketSource owned,
      int targetRate,
      long initialMediaFrame,
      Mapper mapper,
      AacPacketDecoder.Expectation aacRecovery)
      throws IOException {
    source = java.util.Objects.requireNonNull(owned);
    this.aacRecovery = aacRecovery;
    this.targetRate = targetRate;
    this.mapper = java.util.Objects.requireNonNull(mapper);
    position = initialMediaFrame;
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
      case "mp4a" ->
          aacRecovery == null ? PacketCodecs.aac(f) : PacketCodecs.aacWithRecovery(f, aacRecovery);
      case "alac" -> PacketCodecs.alac(f);
      case "opus" -> PacketCodecs.opus(f);
      case "vorbis" -> PacketCodecs.vorbis(f);
      default -> throw new IOException("Unsupported packet codec " + f.codec());
    };
  }

  private void load() throws IOException {
    while (!eof && silence == 0 && !pcm.hasRemaining()) {
      if (closed.get()) throw new IOException("Packet decoder closed");
      if (normalizer != null) {
        var chunk = normalizer.pull(4096);
        if (chunk != null) {
          pcm = chunk.pcm();
          long start = chunk.targetFrameStart();
          if (start < position) {
            int drop = (int) Math.min(position - start, chunk.frames());
            pcm.position(drop * format.frameBytes());
            start += drop;
          }
          if (pcm.hasRemaining()) silence = Math.max(0, start - position);
          continue;
        }
        if (normalizer.isFinished()) {
          eof = true;
          return;
        }
        if (!normalizer.canAccept()) throw new IOException("Packet presentation made no progress");
      }
      var packet = source.next();
      if (packet == null) {
        if (codec != null && codec.finish() != null)
          throw new IOException("Unexpected buffered packet decoder output");
        if (normalizer == null) {
          eof = true;
          return;
        }
        normalizer.finish();
        continue;
      }
      if (codec == null || !packet.format().equals(codecFormat) || packet.discontinuity()) {
        pendingDiscontinuity |= codec != null;
        if (codec != null) codec.close();
        codecFormat = packet.format();
        codec = create(codecFormat);
      }
      var result = codec.decode(packet);
      if (result == null) continue;
      if (codec instanceof AacPacketDecoder aac) {
        var recovery = aac.recovery();
        if (recovery.valid())
          aacExpectation =
              new AacPacketDecoder.Expectation(
                  result.format().sampleRate(),
                  result.format().channels(),
                  recovery.sbrPresent(),
                  recovery.psPresent());
      }
      if (pendingDiscontinuity && !result.source().discontinuity()) {
        var original = result.source();
        var marked =
            new PacketSource.Packet(
                original.format(),
                original.decodeTime(),
                original.presentationTime(),
                original.duration(),
                original.discardStart(),
                original.discardEnd(),
                true,
                original.encoded());
        result = new DecodedPacket(marked, result.format(), result.pcm());
      }
      pendingDiscontinuity = false;
      result = mapper.map(result);
      if (normalizer == null) {
        initialSourceFormat = result.format();
        firstDecodedTime = result.source().presentationTime();
        normalizer =
            new PacketPcmNormalizer(targetRate == 0 ? result.format().sampleRate() : targetRate);
        format = normalizer.format();
      }
      if (!normalizer.accept(result)) throw new IOException("Packet presentation backpressure");
    }
  }

  public boolean hasDownmixedChannels() {
    return normalizer != null && normalizer.hasDownmixedChannels();
  }

  public AacPacketDecoder.Expectation aacExpectation() {
    return aacExpectation;
  }

  public PcmFormat initialSourceFormat() {
    return initialSourceFormat;
  }

  public PacketSource.Time firstDecodedTime() {
    return firstDecodedTime;
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
      if (normalizer != null) normalizer.close();
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
