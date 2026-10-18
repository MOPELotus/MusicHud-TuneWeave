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
    var d = new JLayerStreamDecoder(owned);
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
    var d = new WavPackExtendedCore(owned);
    var enc =
        switch (d.bits()) {
          case 8 -> PcmFormat.Encoding.S8;
          case 16 -> PcmFormat.Encoding.S16LE;
          case 24 -> PcmFormat.Encoding.S24LE;
          case 32 -> PcmFormat.Encoding.S32LE;
          default -> throw new IOException("Unsupported WV precision");
        };
    return new ChunkDecoder(
        format(d.sampleRate(), d.channels(), enc, false),
        new ChunkDecoder.Core() {
          public byte[] next() throws IOException {
            return d.next();
          }

          public void close() throws IOException {
            d.close();
          }
        });
  }
}
