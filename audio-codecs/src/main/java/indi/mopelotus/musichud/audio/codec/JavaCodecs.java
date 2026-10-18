package indi.mopelotus.musichud.audio.codec;

import indi.mopelotus.musichud.audio.codec.internal.*;
import java.io.*;

/** Explicit codec dispatch, with no SPI scanning, reflection, decoder threads or network client. */
public final class JavaCodecs {
  private JavaCodecs() {}

  private static PcmFormat format(
      int rate, int channels, PcmFormat.Encoding encoding, boolean vorbis) {
    return new PcmFormat(
        rate,
        channels,
        encoding,
        channels == 1
            ? PcmFormat.Layout.MONO
            : channels == 2
                ? PcmFormat.Layout.STEREO
                : vorbis ? PcmFormat.Layout.VORBIS : PcmFormat.Layout.UNSPECIFIED);
  }

  public static PcmDecoder mp3(InputStream owned) throws IOException {
    return mp3(owned, JLayerStreamDecoder.TimingMode.EMBEDDED_GAPLESS);
  }

  /**
   * Raw media frames for a container-owned presentation timeline. Does not skip metadata frames,
   * encoder delay, decoder delay or padding; the caller applies authoritative container edits once.
   * This stream API does not flatten or interpret source packet timestamps on the caller's behalf.
   */
  public static PcmDecoder mp3RawMedia(InputStream owned) throws IOException {
    return mp3(owned, JLayerStreamDecoder.TimingMode.RAW_MEDIA);
  }

  private static PcmDecoder mp3(InputStream owned, JLayerStreamDecoder.TimingMode timing)
      throws IOException {
    var d = new JLayerStreamDecoder(owned, timing);
    return new ChunkDecoder(
        format(d.sampleRate(), d.channels(), PcmFormat.Encoding.S16LE, false),
        new ChunkDecoder.Core() {
          public byte[] next() throws IOException {
            return d.readChunk();
          }

          public void close() throws IOException {
            d.close();
          }
        });
  }

  /**
   * Reopens an unchanged MP3 snapshot at plan.byteOffset(). Frame position starts at the plan's
   * global audible sample. The plan must come from this index; this method owns the input.
   */
  public static PcmDecoder mp3Indexed(
      InputStream ownedAtOffset, Mp3SeekIndex index, Mp3SeekIndex.Plan plan) throws IOException {
    final IndexedMp3Decoder d;
    try {
      d = new IndexedMp3Decoder(ownedAtOffset, index.core(), Mp3SeekIndex.corePlan(plan));
    } catch (IOException | RuntimeException failure) {
      // IndexedMp3Decoder owns closure after construction starts; these validation failures occur
      // earlier.
      if (index == null || plan == null) {
        try {
          ownedAtOffset.close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
      }
      throw failure;
    }
    int frameBytes = d.channels() * 2;
    return new ChunkDecoder(
        format(d.sampleRate(), d.channels(), PcmFormat.Encoding.S16LE, false),
        new ChunkDecoder.Core() {
          public byte[] next() throws IOException {
            byte[] b = new byte[1152 * frameBytes];
            int n = d.readChunk(b, 0, b.length);
            return n < 0 ? null : java.util.Arrays.copyOf(b, n);
          }

          public void close() throws IOException {
            d.close();
          }
        },
        plan.audibleSample());
  }

  public static PcmDecoder ogg(InputStream owned) throws IOException {
    var d = new JOrbisOggDecoder(owned);
    return new ChunkDecoder(
        format(d.sampleRate, d.channels, PcmFormat.Encoding.S16LE, true),
        new ChunkDecoder.Core() {
          public byte[] next() throws IOException {
            return d.readChunk();
          }

          public void close() throws IOException {
            d.close();
          }
        });
  }

  public static PcmDecoder wavpack(InputStream owned) throws IOException {
    var d = new WavPackMultiCore(owned);
    try {
      var enc =
          d.isFloating()
              ? PcmFormat.Encoding.F32LE
              : switch (d.bits()) {
                case 8 -> PcmFormat.Encoding.S8;
                case 16 -> PcmFormat.Encoding.S16LE;
                case 24 -> PcmFormat.Encoding.S24LE;
                case 32 -> PcmFormat.Encoding.S32LE;
                default -> throw new IOException("Unsupported WV precision");
              };
      return new ChunkDecoder(
          new PcmFormat(
              d.sampleRate(),
              d.channels(),
              enc,
              PcmFormat.Layout.UNSPECIFIED,
              PcmFormat.SpeakerLayout.fromWaveMask(d.channelMask())),
          new ChunkDecoder.Core() {
            public byte[] next() throws IOException {
              return d.next();
            }

            public void close() throws IOException {
              d.close();
            }
          });
    } catch (IOException | RuntimeException e) {
      try {
        d.close();
      } catch (IOException close) {
        e.addSuppressed(close);
      }
      throw new IOException("Unsupported WavPack PCM format", e);
    }
  }

  public static PcmDecoder aiffAu(InputStream owned) throws IOException {
    return new AiffAuPcmDecoder(owned);
  }

  public static PcmDecoder wma(InputStream owned) throws IOException {
    return new indi.mopelotus.musichud.audio.codec.internal.wma.WmaPcmDecoder(owned);
  }

  public static PcmDecoder ape(InputStream owned) throws IOException {
    return new indi.mopelotus.musichud.audio.codec.internal.ape.ApePcmDecoder(owned);
  }
}
