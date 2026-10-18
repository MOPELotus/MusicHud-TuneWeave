package indi.mopelotus.musichud.audio.container.hls;

import java.io.*;
import java.net.*;
import java.util.*;

public class HlsSessionRegressionTest {
  static int n;
  static URI base = URI.create("https://example.com/live/index.m3u8");

  interface Call {
    void run() throws Exception;
  }

  static void ok(boolean b) throws Exception {
    n++;
    if (!b) throw new Exception("Assertion " + n);
  }

  static void bad(Call c) throws Exception {
    n++;
    try {
      c.run();
      throw new Exception("Expected IOException " + n);
    } catch (IOException expected) {
    }
  }

  static HlsManifest.Manifest media(long seq, String files, boolean end) throws IOException {
    return HlsManifest.parse(
        base,
        "#EXTM3U\n#EXT-X-TARGETDURATION:4\n#EXT-X-MEDIA-SEQUENCE:"
            + seq
            + "\n"
            + files
            + (end ? "#EXT-X-ENDLIST\n" : ""));
  }

  @org.junit.jupiter.api.Test
  void reloadRoutingAndLifecycle() throws Exception {
    String one = "#EXTINF:4,\na.ts\n", two = "#EXTINF:4,\nb.ts\n", three = "#EXTINF:4,\nc.ts\n";
    var s = new HlsSession();
    long g = s.generation();
    var a = media(10, one + two, false);
    ok(s.reload(g, a).size() == 2);
    ok(s.reload(g, a).isEmpty());
    ok(s.reload(g, media(11, two + three, false)).size() == 1);
    bad(() -> s.reload(g, media(10, one + two, false)));
    bad(() -> s.reload(g, media(12, one, false)));
    bad(() -> s.reload(g, media(14, one, false)));
    ok(s.reloadDelayMillis(a, true) == 4000);
    ok(s.reloadDelayMillis(a, false) == 2000);
    ok(s.reload(g, media(12, three, true)).isEmpty());
    bad(() -> s.reload(g, a));
    s.reset();
    bad(() -> s.reload(g, a));
    long g2 = s.generation();
    final long stale = g2 - 1;
    int[] closed = {0};
    bad(() -> s.attach(stale, () -> closed[0]++));
    ok(closed[0] == 1);
    s.attach(g2, () -> closed[0]++);
    s.close();
    ok(closed[0] == 2);
    bad(s::generation);
    var master =
        HlsManifest.parse(
            base,
            "#EXTM3U\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"English\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"en.m3u8\"\n"
                + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"audio\",NAME=\"Chinese\",LANGUAGE=\"zh\",AUTOSELECT=YES,URI=\"zh.m3u8\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=120000,AUDIO=\"audio\"\n"
                + "video.m3u8\n");
    var session = new HlsSession();
    ok(master.variants().getFirst().audioGroup().equals("audio"));
    ok(session.selectAudio(master, "audio", "zh").name().equals("Chinese"));
    ok(session.selectAudio(master, "audio", null).name().equals("English"));
    bad(() -> session.selectAudio(master, "unknown", null));
    bad(
        () ->
            HlsManifest.parse(
                base, "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"a\",DEFAULT=YES\n"));
    bad(() -> HlsManifest.parse(base, "#EXTM3U\n#EXT-X-TARGETDURATION:0\n"));
    bad(
        () ->
            HlsManifest.parse(base, "#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:9223372036854775807\n" + one));
    byte[] iv = new byte[16];
    var key = new HlsManifest.Key(base, iv);
    iv[0] = 1;
    ok(key.explicitIv()[0] == 0);
    byte[] access = key.explicitIv();
    access[0] = 2;
    ok(key.explicitIv()[0] == 0);
    var concurrent = new HlsSession();
    long cg = concurrent.generation();
    concurrent.attach(
        cg,
        () -> {
          Thread t =
              Thread.ofPlatform()
                  .start(
                      () -> {
                        try {
                          concurrent.generation();
                        } catch (IOException expected) {
                        }
                      });
          t.join(2000);
          if (t.isAlive())
            throw new AssertionError("Cancellation held monitor while closing resource");
        });
    concurrent.close();
    ok(true);
  }
}
