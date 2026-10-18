package indi.mopelotus.musichud.audio.codec;

import com.beatofthedrum.alacdecoder.AlacPacketDecoder;
import indi.mopelotus.musichud.audio.codec.internal.OpusPacketDecoder;
import indi.mopelotus.musichud.audio.codec.internal.VorbisPacketDecoder;
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

  /** Raw 48kHz Opus packets. Applies header gain, but not preSkip or container delay/padding. */
  public static PacketDecoder opus(PacketSource.Format initial) throws IOException {
    requireCodec(initial, "opus");
    OpusPacketDecoder core = new OpusPacketDecoder(initial.codecPrivate());
    var format =
        new PcmFormat(
            48000,
            core.channels(),
            PcmFormat.Encoding.S16LE,
            core.channels() == 1
                ? PcmFormat.Layout.MONO
                : core.channels() == 2 ? PcmFormat.Layout.STEREO : PcmFormat.Layout.VORBIS);
    return new Base(initial, format) {
      protected byte[] decodeBytes(PacketSource.Packet packet) throws IOException {
        return core.decodePacket(packet.encoded());
      }

      protected void release() {
        core.close();
      }
    };
  }

  /** Raw Vorbis packets with Xiph-laced headers. Container delay and padding are not applied. */
  public static PacketDecoder vorbis(PacketSource.Format initial) throws IOException {
    requireCodec(initial, "vorbis");
    VorbisPacketDecoder core = new VorbisPacketDecoder(initial.codecPrivate());
    var format =
        new PcmFormat(
            core.sampleRate(),
            core.channels(),
            PcmFormat.Encoding.S16LE,
            core.channels() == 1
                ? PcmFormat.Layout.MONO
                : core.channels() == 2 ? PcmFormat.Layout.STEREO : PcmFormat.Layout.VORBIS);
    return new Base(initial, format) {
      protected byte[] decodeBytes(PacketSource.Packet packet) throws IOException {
        return core.decodePacket(packet.encoded());
      }

      protected void release() {
        core.close();
      }
    };
  }

  public static PacketDecoder alac(PacketSource.Format initial) throws IOException {
    requireCodec(initial, "alac");
    AlacPacketDecoder core = new AlacPacketDecoder(initial.codecPrivate());
    var f = core.format();
    var format =
        pcm(
            f.sampleRate(),
            f.channels(),
            f.bits() > 16 ? PcmFormat.Encoding.S24LE : PcmFormat.Encoding.S16LE);
    return new Base(initial, format) {
      protected byte[] decodeBytes(PacketSource.Packet packet) throws IOException {
        return core.decode(packet.encoded());
      }

      protected void release() {
        core.close();
      }
    };
  }

  /**
   * Common LC, dual-rate HE and HEv2 decoding, retaining existing PCM behavior while exposing
   * read-only AAC observations. Single-rate SBR is explicitly unsupported by this decoder core.
   */
  public static PacketDecoder aac(PacketSource.Format initial) throws IOException {
    return new Aac(initial, null);
  }

  /**
   * Decodes a fresh AAC recovery epoch using output/features verified from this same source. Before
   * recovery is ready, successful access units advance codec state but return no PCM and do not
   * freeze the output format. Read recovery() after each decode; readiness does not replace
   * measured filter preroll or authoritative container timing.
   */
  public static AacPacketDecoder aacWithRecovery(
      PacketSource.Format initial, AacPacketDecoder.Expectation expected) throws IOException {
    return new Aac(initial, Objects.requireNonNull(expected, "expected"));
  }

  private static final class Aac extends Base implements AacPacketDecoder {
    private final Decoder core;
    private final Expectation expected;
    private final SampleBuffer output = new SampleBuffer();
    private boolean requireSbr, requirePs, seenSbr, seenPs;
    private long accessUnit;
    private Recovery recovery = invalid(0);

    Aac(PacketSource.Format initial, Expectation expected) throws IOException {
      super(initial, null);
      requireCodec(initial, "mp4a");
      this.expected = expected;
      try {
        core = Decoder.create(initial.codecPrivate());
      } catch (RuntimeException e) {
        throw new IOException("Invalid AAC configuration", e);
      }
      var extension = core.getConfig().getExtObjectType();
      boolean signaledSbr =
          core.getConfig().hasOutputFrequencyExtension()
              && (extension == net.sourceforge.jaad.aac.Profile.AAC_SBR
                  || extension == net.sourceforge.jaad.aac.Profile.AAC_PS);
      if (signaledSbr
          && core.getConfig().getOutputFrequency().getFrequency()
              == core.getConfig().getSampleFrequency().getFrequency()) {
        // This existing core limitation is separate from recovery observation; no DSP fallback.
        throw new IOException("Unsupported AAC mode: single-rate SBR");
      }

      requirePs =
          core.getConfig().hasParametricStereoSignaling()
              || extension == net.sourceforge.jaad.aac.Profile.AAC_PS
              || expected != null && expected.ps();
      requireSbr = requirePs || signaledSbr || expected != null && expected.sbr();
    }

    public Recovery recovery() {
      return recovery;
    }

    private static Recovery invalid(long au) {
      return new Recovery(
          au, false, 0, 0, false, false, false, false, false, false, false, false, false, false,
          false);
    }

    protected byte[] decodeBytes(PacketSource.Packet packet) throws IOException {
      recovery = invalid(++accessUnit);
      ByteBuffer encoded = packet.encoded();
      if (encoded.remaining() > 1024 * 1024) throw new IOException("AAC packet exceeds bound");
      byte[] b = new byte[encoded.remaining()];
      encoded.get(b);
      output.setBigEndian(false);
      core.decodeFrame(b, output);
      var info = core.getRecoveryInfo();
      var elements = info.elements();
      seenSbr |= elements.stream().anyMatch(Decoder.ElementRecovery::sbrPresent);
      seenPs |= elements.stream().anyMatch(Decoder.ElementRecovery::psPresent);
      requirePs |= seenPs;
      requireSbr |=
          requirePs
              || seenSbr
              || info.initialOutputRate() > info.coreRate()
              || info.observedRate() > info.coreRate()
              || expected != null && expected.sampleRate() > info.coreRate();
      // JAAD duplicates mono without PS, so an output/core channel difference is not PS proof.
      int declared = core.getConfig().getChannelConfiguration().getChannelCount();
      int activeChannels = 0;
      for (var element : elements) {
        activeChannels +=
            switch (element.type()) {
              case "CPE" -> 2;
              case "SCE", "LFE" -> 1;
              default -> 0;
            };
      }
      boolean topology = !elements.isEmpty() && declared > 0 && declared == activeChannels;
      boolean band = topology && (!requireSbr || info.allSbrReady());
      boolean stereo = topology && (!requirePs || info.allPsReady());
      boolean formatMatches =
          expected == null
              || info.observedRate() == expected.sampleRate()
                  && info.observedChannels() == expected.channels();
      recovery =
          new Recovery(
              accessUnit,
              info.successfulAu(),
              info.observedRate(),
              info.observedChannels(),
              seenSbr,
              seenPs,
              band && (expected == null || info.observedRate() == expected.sampleRate()),
              stereo && (expected == null || info.observedChannels() == expected.channels()),
              !elements.isEmpty()
                  && seenSbr
                  && elements.stream().allMatch(Decoder.ElementRecovery::sbrHeaderThisAu),
              !elements.isEmpty()
                  && seenPs
                  && elements.stream().allMatch(Decoder.ElementRecovery::psHeaderThisAu),
              !elements.isEmpty()
                  && seenSbr
                  && elements.stream().allMatch(Decoder.ElementRecovery::sbrIndependentThisAu),
              !elements.isEmpty()
                  && seenPs
                  && elements.stream().allMatch(Decoder.ElementRecovery::psIndependentThisAu),
              band && stereo && formatMatches,
              info.topologyChanged(),
              info.configurationChanged());
      if (expected != null && !recovery.ready()) return null;
      if (output.getData().length == 0) return null;
      if (output.getBitsPerSample() != 16) throw new IOException("Unexpected AAC precision");
      PcmFormat next = pcm(output.getSampleRate(), output.getChannels(), PcmFormat.Encoding.S16LE);
      if (decodedFormat == null) decodedFormat = next;
      else if (!decodedFormat.equals(next)) throw new IOException("AAC output format changed");
      return output.getData();
    }

    protected void release() {
      recovery = invalid(accessUnit);
      output.setData(new byte[0], 1, 1, 16, 0);
    }
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
