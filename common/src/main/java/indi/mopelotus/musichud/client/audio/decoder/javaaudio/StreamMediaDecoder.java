package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.audio.codec.DecodedPacket;
import indi.mopelotus.musichud.audio.codec.PacketSource;
import indi.mopelotus.musichud.audio.codec.PcmDecoder;
import indi.mopelotus.musichud.audio.codec.PcmFormat;
import indi.mopelotus.musichud.client.audio.decoder.PacketPcmNormalizer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Presents already-trimmed logical PCM links through one bounded, fixed device format. */
public final class StreamMediaDecoder implements PcmDecoder, PcmChannelConversion {
  /**
   * Transfers one decoder at a time; null is physical EOF. Closing must cancel the current link and
   * any pending nextLink/read IO, including a link being opened. Decoders tolerate repeat close.
   * Neither this input nor its returned decoders may pre-scan subsequent logical links.
   */
  public interface Input extends AutoCloseable {
    PcmDecoder nextLink() throws IOException;

    @Override
    void close() throws IOException;
  }

  private static final PacketSource.Format SYNTHETIC =
      new PacketSource.Format("pcm-link", new byte[0]);
  private final Input input;
  private final Object readerLock = new Object();
  private final AtomicBoolean closed = new AtomicBoolean();
  private final PacketPcmNormalizer normalizer;
  private final PcmFormat format;
  private PcmDecoder decoder;
  private PcmFormat linkFormat;
  private byte[] scratch;
  private ByteBuffer pending;
  private long pendingStart, sourceFrame;
  private volatile long position;
  private volatile boolean downmixed;
  private boolean firstInLink = true, laterLink, physicalEof;

  /** Opens only the first link. The first link sample rate becomes the fixed output rate. */
  public StreamMediaDecoder(Input owned) throws IOException {
    this(owned, 0, 0, 0);
  }

  /**
   * Explicit seek constructor. initialFrame is the absolute target-grid frame returned by
   * framePosition; firstLinkSourceFrame is the absolute source-grid position of the next PCM frame
   * in the first decoder. Earlier normalized output is discarded. A differing rate requires the
   * caller to supply source preroll for filter warmup; equal-rate presentation bypasses resampling.
   * Later links start at their own zero and append at the previous exact end. targetRate=0 selects
   * the first link rate. Codec pre-skip, trailing trim and container edits are never applied here.
   */
  public StreamMediaDecoder(
      Input owned, int targetRate, long initialFrame, long firstLinkSourceFrame)
      throws IOException {
    input = Objects.requireNonNull(owned);
    PacketPcmNormalizer created = null;
    try {
      if (initialFrame < 0 || firstLinkSourceFrame < 0)
        throw new IOException("Negative PCM origin");
      decoder = input.nextLink();
      if (decoder == null) throw new IOException("No PCM links");
      linkFormat = decoder.format();
      created = new PacketPcmNormalizer(targetRate == 0 ? linkFormat.sampleRate() : targetRate);
      format = created.format();
      normalizer = created;
      sourceFrame = firstLinkSourceFrame;
      position = initialFrame;
      prepareLink();
    } catch (IOException | RuntimeException failure) {
      try {
        input.close();
      } catch (Exception close) {
        failure.addSuppressed(close);
      }
      if (decoder != null)
        try {
          decoder.close();
        } catch (Exception close) {
          failure.addSuppressed(close);
        }
      if (created != null) created.close();
      throw failure;
    }
  }

  @Override
  public PcmFormat format() {
    return format;
  }

  @Override
  public long framePosition() {
    return position;
  }

  @Override
  public boolean hasDownmixedChannels() {
    return downmixed || normalizer.hasDownmixedChannels();
  }

  @Override
  public int readFrames(byte[] target, int offset, int maxFrames) throws IOException {
    Objects.requireNonNull(target);
    if (maxFrames < 0
        || offset < 0
        || offset > target.length
        || (long) maxFrames * format.frameBytes() > target.length - offset)
      throw new IndexOutOfBoundsException("PCM destination");
    synchronized (readerLock) {
      requireOpen();
      if (maxFrames == 0) return 0;
      while (true) {
        requireOpen();
        if (pending != null) {
          int frames = pending.remaining() / format.frameBytes();
          if (pendingStart < position) {
            int skip = (int) Math.min(frames, position - pendingStart);
            pending.position(pending.position() + skip * format.frameBytes());
            pendingStart += skip;
            frames -= skip;
          }
          if (frames == 0) {
            pending = null;
            continue;
          }
          int count =
              (int) Math.min(maxFrames, pendingStart > position ? pendingStart - position : frames);
          if (pendingStart > position)
            Arrays.fill(target, offset, offset + count * format.frameBytes(), (byte) 0);
          else {
            pending.get(target, offset, count * format.frameBytes());
            pendingStart += count;
          }
          position = Math.addExact(position, count);
          return count;
        }
        var chunk = normalizer.pull(4096);
        downmixed |= normalizer.hasDownmixedChannels();
        if (chunk != null) {
          pending = chunk.pcm();
          pendingStart = chunk.targetFrameStart();
          continue;
        }
        if (normalizer.isFinished()) return -1;
        if (!normalizer.canAccept()) throw new IOException("PCM normalizer made no progress");
        if (physicalEof) {
          normalizer.finish();
          continue;
        }
        int frames = decoder.readFrames(scratch, 0, 2048);
        requireOpen();
        if (frames == -1) {
          downmixed |=
              decoder instanceof PcmChannelConversion conversion
                  && conversion.hasDownmixedChannels();
          decoder.close();
          decoder = null;
          PcmDecoder next = input.nextLink();
          if (closed.get()) {
            if (next != null) next.close();
            requireOpen();
          }
          decoder = next;
          if (next == null) {
            physicalEof = true;
            normalizer.finish();
            continue;
          }
          sourceFrame = 0;
          laterLink = true;
          firstInLink = true;
          prepareLink();
          continue;
        }
        if (frames <= 0 || frames > 2048) throw new IOException("Invalid PCM decoder frame count");
        if (!decoder.format().equals(linkFormat))
          throw new IOException("PCM format changed inside logical link");
        downmixed |=
            decoder instanceof PcmChannelConversion conversion && conversion.hasDownmixedChannels();
        var start = new PacketSource.Time(sourceFrame, linkFormat.sampleRate());
        var duration = new PacketSource.Time(frames, linkFormat.sampleRate());
        var source =
            new PacketSource.Packet(
                SYNTHETIC,
                start,
                start,
                duration,
                PacketSource.Time.ZERO,
                PacketSource.Time.ZERO,
                firstInLink && laterLink,
                ByteBuffer.allocate(0));
        var decoded =
            new DecodedPacket(
                source, linkFormat, ByteBuffer.wrap(scratch, 0, frames * linkFormat.frameBytes()));
        if (!normalizer.accept(decoded, firstInLink && laterLink))
          throw new IOException("Unexpected PCM backpressure");
        sourceFrame = Math.addExact(sourceFrame, frames);
        firstInLink = false;
      }
    }
  }

  private void prepareLink() throws IOException {
    linkFormat = decoder.format();
    int size;
    try {
      size = Math.multiplyExact(2048, linkFormat.frameBytes());
    } catch (ArithmeticException invalid) {
      throw new IOException("PCM frame size", invalid);
    }
    if (size <= 0 || size > PacketSource.MAX_PACKET_BYTES) throw new IOException("PCM frame size");
    scratch = new byte[size];
  }

  private void requireOpen() throws IOException {
    if (closed.get()) throw new IOException("PCM presentation closed");
  }

  @Override
  public void close() throws IOException {
    if (!closed.compareAndSet(false, true)) return;
    IOException failure = null;
    try {
      input.close();
    } catch (IOException invalid) {
      failure = invalid;
    }
    synchronized (readerLock) {
      if (decoder != null)
        try {
          decoder.close();
        } catch (IOException invalid) {
          if (failure == null) failure = invalid;
          else failure.addSuppressed(invalid);
        }
      decoder = null;
      scratch = null;
      pending = null;
      normalizer.close();
    }
    if (failure != null) throw failure;
  }
}
