package indi.mopelotus.musichud.audio.codec;

import com.beatofthedrum.alacdecoder.AlacPacketDecoder;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import net.sourceforge.jaad.SampleBuffer;
import net.sourceforge.jaad.aac.Decoder;

/**
 * Packet codecs preserve timing for the presentation layer instead of flattening it into a stream.
 */
public final class PacketCodecs {
  private PacketCodecs() {}

  public static PacketDecoder alac(PacketSource.Format initial) throws IOException {
    requireCodec(initial, "alac");
    AlacPacketDecoder core = new AlacPacketDecoder(initial.codecPrivate());
    var f = core.format();
    var format =
        pcm(
            f.sampleRate(),
            f.channels(),
            f.bits() == 24 ? PcmFormat.Encoding.S24LE : PcmFormat.Encoding.S16LE);
    return new Base(initial, format) {
      protected byte[] decodeBytes(PacketSource.Packet packet) throws IOException {
        return core.decode(packet.encoded());
      }

      protected void release() {
        core.close();
      }
    };
  }

  public static PacketDecoder aac(PacketSource.Format initial) throws IOException {
    requireCodec(initial, "mp4a");
    final Decoder core;
    try {
      core = Decoder.create(initial.codecPrivate());
    } catch (RuntimeException e) {
      throw new IOException("Invalid AAC configuration", e);
    }
    return new Base(initial, null) {
      private final SampleBuffer output = new SampleBuffer();

      protected byte[] decodeBytes(PacketSource.Packet packet) throws IOException {
        ByteBuffer encoded = packet.encoded();
        if (encoded.remaining() > 1024 * 1024) throw new IOException("AAC packet exceeds bound");
        byte[] b = new byte[encoded.remaining()];
        encoded.get(b);
        output.setBigEndian(false);
        core.decodeFrame(b, output);
        if (output.getData().length == 0) return null;
        if (output.getBitsPerSample() != 16) throw new IOException("Unexpected AAC precision");
        PcmFormat next =
            pcm(output.getSampleRate(), output.getChannels(), PcmFormat.Encoding.S16LE);
        if (decodedFormat == null) decodedFormat = next;
        else if (!decodedFormat.equals(next)) throw new IOException("AAC output format changed");
        return output.getData();
      }

      protected void release() {
        output.setData(new byte[0], 1, 1, 16, 0);
      }
    };
  }

  private static PcmFormat pcm(int rate, int channels, PcmFormat.Encoding e) {
    return new PcmFormat(
        rate,
        channels,
        e,
        channels == 1
            ? PcmFormat.Layout.MONO
            : channels == 2 ? PcmFormat.Layout.STEREO : PcmFormat.Layout.UNSPECIFIED);
  }

  private static void requireCodec(PacketSource.Format f, String codec) throws IOException {
    Objects.requireNonNull(f, "format");
    if (!codec.equals(f.codec())) throw new IOException("Expected " + codec + " codec");
  }

  private abstract static class Base implements PacketDecoder {
    private final PacketSource.Format initial;
    protected PcmFormat decodedFormat;
    private boolean started, closed, finished;

    Base(PacketSource.Format initial, PcmFormat format) {
      this.initial = initial;
      decodedFormat = format;
    }

    public Optional<PcmFormat> format() {
      return Optional.ofNullable(decodedFormat);
    }

    protected abstract byte[] decodeBytes(PacketSource.Packet packet) throws IOException;

    protected abstract void release() throws IOException;

    private void ensureOpen() throws IOException {
      if (closed || finished) throw new IOException("Packet decoder closed or finished");
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Packet decode interrupted");
    }

    public DecodedPacket decode(PacketSource.Packet packet) throws IOException {
      ensureOpen();
      Objects.requireNonNull(packet, "packet");
      try {
        if (!initial.equals(packet.format()) || started && packet.discontinuity())
          throw new IOException("Packet stream changed; create a new decoder");
        started = true;
        byte[] pcm = decodeBytes(packet);
        if (pcm == null || pcm.length == 0) return null;
        return new DecodedPacket(packet, decodedFormat, ByteBuffer.wrap(pcm));
      } catch (IOException | RuntimeException error) {
        IOException failure =
            error instanceof IOException io ? io : new IOException("Codec packet failed", error);
        try {
          close();
        } catch (IOException c) {
          failure.addSuppressed(c);
        }
        throw failure;
      }
    }

    public DecodedPacket finish() throws IOException {
      if (finished) return null;
      ensureOpen();
      finished = true;
      return null;
    }

    public void close() throws IOException {
      if (!closed) {
        closed = true;
        release();
      }
    }
  }
}
