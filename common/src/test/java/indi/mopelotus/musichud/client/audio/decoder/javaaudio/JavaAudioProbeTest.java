package indi.mopelotus.musichud.client.audio.decoder.javaaudio;

import indi.mopelotus.musichud.client.audio.decoder.javaaudio.JavaAudioProbe.Container;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class JavaAudioProbeTest {
  private static int checks;

  private static byte[] b(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  private static byte[] concat(byte[]... arrays) {
    var out = new ByteArrayOutputStream();
    for (var a : arrays) out.writeBytes(a);
    return out.toByteArray();
  }

  private static void yes(boolean condition, String message) {
    checks++;
    if (!condition) throw new AssertionError(message);
  }

  private static final class Paced extends ByteArrayInputStream {
    int readBytes, closes, limit;

    Paced(byte[] bytes, int limit) {
      super(bytes);
      this.limit = limit;
    }

    @Override
    public synchronized int read(byte[] bytes, int off, int len) {
      if (readBytes >= limit)
        throw new AssertionError("probe overread before classification: " + readBytes);
      int n = super.read(bytes, off, Math.min(len, 1));
      if (n > 0) readBytes += n;
      return n;
    }

    @Override
    public synchronized int read() {
      if (readBytes >= limit) throw new AssertionError("probe overread scalar");
      int n = super.read();
      if (n >= 0) readBytes++;
      return n;
    }

    @Override
    public void close() {
      closes++;
    }
  }

  private static void sample(Container kind, byte[] prefix, int expectedReads) throws Exception {
    byte[] source = concat(prefix, b("unread-tail-must-survive"));
    var input = new Paced(source, expectedReads);
    var prepared = JavaAudioProbe.prepare(input);
    yes(prepared.container() == kind, "kind " + kind + " got " + prepared.container());
    yes(input.readBytes == expectedReads, "minimal reads " + kind + ": " + input.readBytes);
    input.limit = Integer.MAX_VALUE;
    yes(Arrays.equals(prepared.input().readAllBytes(), source), "replay " + kind);
    prepared.close();
    prepared.close();
    yes(input.closes == 1, "owned close " + kind);
  }

  private static void invalid(byte[] bytes) throws Exception {
    var input = new Paced(bytes, Integer.MAX_VALUE);
    boolean failed = false;
    try (var p = JavaAudioProbe.prepare(input)) {
    } catch (IOException e) {
      failed = true;
    }
    yes(failed, "invalid metadata accepted");
    yes(input.closes == 1, "failure close");
  }

  @org.junit.jupiter.api.Test
  void incrementalProbePreservesOwnedInput() throws Exception {
    sample(Container.FLAC, b("fLaC"), 4);
    sample(Container.OGG, b("OggS"), 4);
    sample(Container.WAV, concat(b("RIFF"), new byte[4], b("WAVE")), 12);
    sample(Container.AIFF, concat(b("FORM"), new byte[4], b("AIFC")), 12);
    sample(Container.AU, b(".snd"), 4);
    sample(Container.APE, b("MAC "), 4);
    sample(Container.WAVPACK, b("wvpk"), 4);
    sample(Container.MATROSKA, new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, 4);
    sample(
        Container.ASF,
        new byte[] {
          0x30,
          0x26,
          (byte) 0xb2,
          0x75,
          (byte) 0x8e,
          0x66,
          (byte) 0xcf,
          0x11,
          (byte) 0xa6,
          (byte) 0xd9,
          0,
          (byte) 0xaa,
          0,
          0x62,
          (byte) 0xce,
          0x6c
        },
        16);
    sample(Container.ISO_BMFF, concat(new byte[] {0, 0, 0, 24}, b("ftyp"), b("ZZZZ")), 8);
    sample(Container.ISO_BMFF, concat(new byte[] {0x30, 0, 0, 24}, b("ftyp")), 8);
    sample(Container.ISO_BMFF, concat(new byte[] {0, 0, 0, 8}, b("free")), 8);
    byte[] adts = {(byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80, 0, (byte) 0xff, (byte) 0xfc};
    sample(Container.ADTS, adts, 7);
    sample(
        Container.ADTS,
        new byte[] {(byte) 0xff, (byte) 0xf0, 0x50, (byte) 0x80, 1, 0x3f, (byte) 0xfc, 0, 0},
        9);
    sample(Container.MPEG_AUDIO, new byte[] {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 0}, 4);
    sample(Container.UNKNOWN, new byte[] {(byte) 0xff, (byte) 0xeb, (byte) 0x90, 0}, 4);
    sample(
        Container.UNKNOWN,
        new byte[] {(byte) 0xff, (byte) 0xf1, 0x7c, (byte) 0x80, 0, (byte) 0xff, (byte) 0xfc},
        7);
    byte[] ts = new byte[380];
    for (int i : new int[] {0, 188, 376}) {
      ts[i] = 0x47;
      ts[i + 3] = 0x10;
    }
    sample(Container.MPEG_TS, ts, 380);
    sample(Container.M3U_CANDIDATE, b("#EXTM3U\n"), 8);
    sample(Container.M3U_CANDIDATE, b("#EXT-X-TARGETDURATION:8"), 8);
    sample(Container.PLS_CANDIDATE, b("[playlist]"), 10);
    sample(Container.URL_PLAYLIST_CANDIDATE, b("https://example.test/x"), 8);
    sample(
        Container.M3U_CANDIDATE,
        concat(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf}, b(" \n#EXTM3U\n")),
        13);
    byte[] id3 = {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 5};
    byte[] audio = concat(adts, b("payload"));
    var input = new Paced(concat(id3, new byte[5], audio), 22);
    var prepared = JavaAudioProbe.prepare(input);
    yes(prepared.container() == Container.ADTS, "ID3+ADTS");
    yes(prepared.skippedMetadataBytes() == 15, "ID3 skip bytes");
    yes(input.readBytes == 22, "ID3 minimum reads");
    input.limit = Integer.MAX_VALUE;
    yes(Arrays.equals(audio, prepared.input().readAllBytes()), "ID3 retains audio");
    prepared.close();
    byte[] footerHeader = {'I', 'D', '3', 4, 0, 0x10, 0, 0, 0, 0};
    try (var p =
        JavaAudioProbe.prepare(
            new ByteArrayInputStream(
                concat(
                    footerHeader,
                    new byte[] {'3', 'D', 'I', 4, 0, 0x10, 0, 0, 0, 0},
                    b("fLaC"))))) {
      yes(p.container() == Container.FLAC && p.skippedMetadataBytes() == 20, "ID3 footer skip");
    }
    invalid(new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, (byte) 0x80, 0});
    invalid(new byte[] {'I', 'D', '3', 4, 0, 0, 0x7f, 0x7f, 0x7f, 0x7f});
    invalid(new byte[] {'I', 'D', '3', 3, 0, 0, 0, 0, 0, 5});
    invalid(new byte[] {'I', 'D', '3', 5, 0, 0, 0, 0, 0, 0});
    try (var p = JavaAudioProbe.prepare(new ByteArrayInputStream(new byte[0]))) {
      yes(p.container() == Container.UNKNOWN, "empty");
      yes(p.input().read() == -1, "empty replay");
    }
    // A magic match is intentionally only a router hint: no fake declaration of valid PCM.
    try (var p = JavaAudioProbe.prepare(new ByteArrayInputStream(b("fLaC")))) {
      yes(p.container() == Container.FLAC, "magic dispatch");
      yes(p.input().readAllBytes().length == 4, "invalid codec still replayed");
    }

    byte[] largeHeader = {'I', 'D', '3', 4, 0, 0, 1, 0, 0, 0}; // 2 MiB cover metadata
    byte[] largeBody = new byte[1 << 21];
    try (var p =
        JavaAudioProbe.prepare(new ByteArrayInputStream(concat(largeHeader, largeBody, audio)))) {
      yes(p.container() == Container.ADTS, "large cover ID3 underlying AAC");
      yes(p.skippedMetadataBytes() == (1 << 21) + 10L, "large cover bounded skip");
      yes(Arrays.equals(p.input().readAllBytes(), audio), "large cover audio survives");
    }
    invalid(concat(footerHeader, new byte[10], b("fLaC")));
    try (var p =
        JavaAudioProbe.prepare(
            new ByteArrayInputStream(concat(id3, new byte[5], id3, new byte[5], audio)))) {
      yes(
          p.skippedMetadataBytes() == 30 && p.container() == Container.ADTS,
          "consecutive ID3 tags");
      yes(Arrays.equals(p.input().readAllBytes(), audio), "consecutive ID3 replay");
    }
    for (byte[] bad :
        new byte[][] {
          {(byte) 0xff, (byte) 0xfb, (byte) 0xfc, 0}, // invalid bitrate/rate
          {(byte) 0xff, (byte) 0xfb, (byte) 0x90, 2}, // reserved emphasis
          {(byte) 0xff, (byte) 0xf1, 0x50, (byte) 0x80, 0, 0, 0} // ADTS frame shorter than header
        }) {
      try (var p = JavaAudioProbe.prepare(new ByteArrayInputStream(bad))) {
        yes(p.container() == Container.UNKNOWN, "malformed sync");
        yes(Arrays.equals(p.input().readAllBytes(), bad), "malformed payload replay");
      }
    }
    var throwing =
        new InputStream() {
          @Override
          public int read() throws IOException {
            throw new IOException("read-failed");
          }

          @Override
          public void close() throws IOException {
            throw new IOException("close-failed");
          }
        };
    try {
      JavaAudioProbe.prepare(throwing);
      throw new AssertionError("exception expected");
    } catch (IOException error) {
      yes(error.getMessage().equals("read-failed"), "preserve primary failure");
      yes(error.getSuppressed().length == 1, "preserve close failure");
    }

    System.out.println("JavaAudioProbe checks=" + checks + " PASS");
  }
}
