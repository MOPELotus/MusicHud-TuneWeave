package indi.mopelotus.musichud.audio.container.input;

import indi.mopelotus.musichud.audio.container.hls.HlsManifest;
import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;

public class InputAdaptersTest {
  static int checks;

  static void yes(boolean value) {
    checks++;
    if (!value) throw new AssertionError("check " + checks);
  }

  interface Action {
    void run() throws Exception;
  }

  static void bad(Action action) throws Exception {
    checks++;
    try {
      action.run();
      throw new AssertionError("accepted malformed " + checks);
    } catch (IOException | IllegalArgumentException expected) {
    }
  }

  static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  static String text(byte[] b) {
    return new String(b, StandardCharsets.UTF_8);
  }

  static URI u(String s) {
    return URI.create(s);
  }

  static void icy() throws Exception {
    byte[] wire;
    try (var out = new ByteArrayOutputStream()) {
      out.write(bytes("abcd"));
      out.write(1);
      out.write(Arrays.copyOf(bytes("StreamTitle=x;"), 16));
      out.write(bytes("efgh"));
      out.write(0);
      out.write(bytes("ij"));
      wire = out.toByteArray();
    }
    for (int chunk : new int[] {1, 2, 3, 20}) {
      List<byte[]> metadata = new ArrayList<>();
      try (var in = new IcyMetadataInputStream(new ByteArrayInputStream(wire), 4, metadata::add)) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[chunk];
        for (int n; (n = in.read(b)) >= 0; ) out.write(b, 0, n);
        yes(text(out.toByteArray()).equals("abcdefghij"));
        yes(metadata.size() == 1 && text(metadata.getFirst()).equals("StreamTitle=x;"));
        yes(in.read() == -1);
      }
    }
    try (var in = new IcyMetadataInputStream(new ByteArrayInputStream(wire), 4, null)) {
      yes(in.skip(7) == 7);
      yes(text(in.readAllBytes()).equals("hij"));
      yes(in.read(new byte[0]) == 0);
    }
    yes(IcyMetadataInputStream.parseInterval(null) == 0);
    yes(IcyMetadataInputStream.parseInterval("16000") == 16000);
    for (String s : List.of("0", "-1", "abc", "1,2", "999999999", "16777217", " 2"))
      bad(() -> IcyMetadataInputStream.parseInterval(s));
    bad(
        () ->
            new IcyMetadataInputStream(new ByteArrayInputStream(new byte[] {1, 1}), 1, null)
                .readAllBytes());
    AtomicBoolean released = new AtomicBoolean();
    var in =
        new IcyMetadataInputStream(
            new ByteArrayInputStream(wire) {
              public void close() {
                released.set(true);
              }
            },
            4,
            null);
    in.close();
    yes(released.get());
    bad(() -> in.read());
    try (var eof = new IcyMetadataInputStream(new ByteArrayInputStream(bytes("abcd")), 4, null)) {
      yes(text(eof.readAllBytes()).equals("abcd"));
    }
  }

  static PlaylistResolver.Loaded loaded(String uri, PlaylistResolver.Kind kind, String body) {
    return new PlaylistResolver.Loaded(u(uri), kind, bytes(body));
  }

  @Test
  void invalidIcyConstructorClosesOwnedInputExactlyOnce() throws Exception {
    AtomicInteger closes = new AtomicInteger();
    InputStream owned =
        new ByteArrayInputStream(new byte[0]) {
          @Override
          public void close() {
            closes.incrementAndGet();
          }
        };
    bad(() -> new IcyMetadataInputStream(owned, 0, null));
    yes(closes.get() == 1);
  }

  static void playlists() throws Exception {
    var map = new HashMap<URI, PlaylistResolver.Loaded>();
    map.put(
        u("file:/tmp/list.m3u"),
        loaded(
            "file:/tmp/list.m3u",
            PlaylistResolver.Kind.M3U,
            "\ufeff#EXTM3U\n#EXTINF:1,x\nsub/list.pls\n"));
    map.put(
        u("file:/tmp/sub/list.pls"),
        loaded(
            "file:/tmp/sub/list.pls",
            PlaylistResolver.Kind.PLS,
            "[playlist]\nFile2=later.mp3\nFile1=https://example.com/a.mp3\n"));
    List<Boolean> policies = new ArrayList<>();
    var t =
        PlaylistResolver.resolve(
            u("file:/tmp/list.m3u"),
            false,
            (uri, pub) -> {
              policies.add(pub);
              return map.getOrDefault(
                  uri, new PlaylistResolver.Loaded(uri, PlaylistResolver.Kind.MEDIA, new byte[0]));
            });
    yes(t.uri().equals(u("https://example.com/a.mp3")));
    yes(policies.equals(List.of(false, false, false)));
    var pub =
        PlaylistResolver.resolve(
            u("https://example.com/list"),
            true,
            (uri, policy) -> {
              yes(policy);
              return uri.getPath().equals("/list")
                  ? loaded(uri.toString(), PlaylistResolver.Kind.M3U, "../a.mp3")
                  : new PlaylistResolver.Loaded(uri, PlaylistResolver.Kind.MEDIA, new byte[0]);
            });
    yes(pub.publicOnly() && pub.uri().getPath().equals("/../a.mp3"));
    bad(() -> PlaylistResolver.resolve(u("file:/tmp/list"), true, (uri, p) -> null));
    bad(
        () ->
            PlaylistResolver.resolve(
                u("https://example.com/list"),
                true,
                (uri, p) -> loaded(uri.toString(), PlaylistResolver.Kind.M3U, "file:/etc/passwd")));
    bad(
        () ->
            PlaylistResolver.resolve(
                u("https://example.com/list"),
                false,
                (uri, p) -> loaded(uri.toString(), PlaylistResolver.Kind.M3U, "file:/etc/passwd")));
    bad(
        () ->
            PlaylistResolver.resolve(
                u("https://example.com/list"),
                true,
                (uri, p) -> loaded(uri.toString(), PlaylistResolver.Kind.M3U, "list")));
    bad(
        () ->
            PlaylistResolver.resolve(
                u("https://example.com/list"),
                true,
                (uri, p) ->
                    loaded(
                        uri.toString(),
                        PlaylistResolver.Kind.PLS,
                        "[playlist]\nFile1=a\nFile1=b")));
    bad(
        () ->
            PlaylistResolver.first("[playlist]\nFile99999999999999=a", PlaylistResolver.Kind.PLS));
    bad(
        () ->
            PlaylistResolver.reference(
                u("https://example.com/list"), "http://u:p@example.com/a", true));
    bad(() -> PlaylistResolver.reference(u("https://example.com/list"), "x\\y", true));
    bad(() -> PlaylistResolver.decode(new byte[] {(byte) 0xff}));
    yes(
        PlaylistResolver.reference(u("https://example.com/list"), "icy://radio.example/a", true)
            .equals(u("http://radio.example/a")));
    var hls =
        PlaylistResolver.resolve(
            u("https://example.com/list"),
            true,
            (uri, p) ->
                loaded(
                    uri.toString(),
                    PlaylistResolver.Kind.M3U,
                    "#EXTM3U\n#EXT-X-TARGETDURATION:1\n"));
    yes(hls.kind() == PlaylistResolver.Kind.HLS);
  }

  static class MemoryFetch implements HlsPlaybackInput.Fetcher {
    Map<URI, byte[]> files = new HashMap<>();
    List<String> opens = new ArrayList<>();
    AtomicInteger resourceCloses = new AtomicInteger(), requestCloses = new AtomicInteger();

    void put(String path, String data) {
      files.put(u("https://example.com/" + path), bytes(data));
    }

    public HlsPlaybackInput.Request request(URI uri, HlsManifest.Range range, boolean pub) {
      yes(pub);
      return new HlsPlaybackInput.Request() {
        public HlsPlaybackInput.Resource open() throws IOException {
          opens.add(uri.getPath());
          byte[] data = files.get(uri);
          if (data == null) throw new IOException("missing " + uri);
          if (range != null)
            data =
                Arrays.copyOfRange(
                    data,
                    Math.toIntExact(range.offset()),
                    Math.toIntExact(range.offset() + range.length()));
          var body = new ByteArrayInputStream(data);
          return new HlsPlaybackInput.Resource() {
            public URI finalUri() {
              return uri;
            }

            public InputStream body() {
              return body;
            }

            public void close() throws IOException {
              resourceCloses.incrementAndGet();
              body.close();
            }
          };
        }

        public void close() {
          requestCloses.incrementAndGet();
        }
      };
    }
  }

  static void hls() throws Exception {
    MemoryFetch f = new MemoryFetch();
    f.put(
        "master",
        "#EXTM3U\n"
            + "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"en\",LANGUAGE=\"en\",DEFAULT=YES,AUTOSELECT=YES,URI=\"audio\"\n"
            + "#EXT-X-STREAM-INF:BANDWIDTH=100,AUDIO=\"a\"\n"
            + "video\n");
    f.put(
        "audio",
        "#EXTM3U\n"
            + "#EXT-X-MEDIA-SEQUENCE:5\n"
            + "#EXT-X-DISCONTINUITY-SEQUENCE:9\n"
            + "#EXT-X-MAP:URI=\"init\"\n"
            + "#EXTINF:1,\n"
            + "#EXT-X-BYTERANGE:3@1\n"
            + "media\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXTINF:1,\n"
            + "#EXT-X-BYTERANGE:2@4\n"
            + "media\n"
            + "#EXT-X-ENDLIST\n");
    f.put("init", "INIT");
    f.put("media", "xABCDEz");
    try (var input = new HlsPlaybackInput(u("https://example.com/master"), true, "en", f)) {
      var a = input.next();
      yes(text(a.media()).equals("ABC"));
      yes(text(a.initialization()).equals("INIT"));
      yes(a.segment().sequence() == 5 && a.discontinuitySequence() == 9 && a.resetContainer());
      yes(text(a.openFragmentedMp4().readAllBytes()).equals("INITABC"));
      var b = input.next();
      yes(text(b.media()).equals("DE") && b.discontinuitySequence() == 10 && b.resetContainer());
      yes(input.next() == null);
      byte[] copy = a.media();
      copy[0] = 0;
      yes(a.media()[0] == 'A');
    }
    yes(f.opens.equals(List.of("/master", "/audio", "/init", "/media", "/init", "/media")));
    yes(f.resourceCloses.get() == 6 && f.requestCloses.get() == 6);
    // AES128 key applies independently to init and media; explicit IV and byte ranges survive the
    // connector.
    byte[] secret = bytes("0123456789abcdef"), iv = new byte[16];
    var cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
    cipher.init(
        1,
        new javax.crypto.spec.SecretKeySpec(secret, "AES"),
        new javax.crypto.spec.IvParameterSpec(iv));
    byte[] encrypted = cipher.doFinal(bytes("payload"));
    MemoryFetch aes = new MemoryFetch();
    aes.put(
        "list",
        "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"key\",IV=0x0\n#EXTINF:1,\nmedia\n#EXT-X-ENDLIST");
    aes.files.put(u("https://example.com/key"), secret);
    aes.files.put(u("https://example.com/media"), encrypted);
    try (var input = new HlsPlaybackInput(u("https://example.com/list"), true, null, aes)) {
      yes(text(input.next().media()).equals("payload"));
      yes(input.next() == null);
    }
    MemoryFetch cycle = new MemoryFetch();
    cycle.put("list", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlist");
    try (var input = new HlsPlaybackInput(u("https://example.com/list"), true, null, cycle)) {
      bad(() -> input.next());
    }
    MemoryFetch remoteFile = new MemoryFetch();
    remoteFile.put("list", "#EXTM3U\n#EXTINF:1,\nfile:/tmp/secret\n#EXT-X-ENDLIST");
    try (var input = new HlsPlaybackInput(u("https://example.com/list"), true, null, remoteFile)) {
      bad(() -> input.next());
    }
    AtomicInteger reloads = new AtomicInteger();
    MemoryFetch live =
        new MemoryFetch() {
          public HlsPlaybackInput.Request request(URI uri, HlsManifest.Range range, boolean pub) {
            if (uri.getPath().equals("/list")) {
              int n = reloads.incrementAndGet();
              put(
                  "list",
                  n == 1
                      ? "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\na\n"
                      : "#EXTM3U\n"
                          + "#EXT-X-TARGETDURATION:1\n"
                          + "#EXTINF:1,\n"
                          + "a\n"
                          + "#EXT-X-DISCONTINUITY\n"
                          + "#EXTINF:1,\n"
                          + "b\n"
                          + "#EXT-X-ENDLIST");
            }
            return super.request(uri, range, pub);
          }
        };
    live.put("a", "A");
    live.put("b", "B");
    AtomicLong wait = new AtomicLong();
    try (var input =
        new HlsPlaybackInput(u("https://example.com/list"), true, null, live, wait::set)) {
      yes(text(input.next().media()).equals("A"));
      var b = input.next();
      yes(text(b.media()).equals("B") && b.discontinuitySequence() == 1);
      yes(wait.get() == 1000);
      yes(input.next() == null);
    }
  }

  static void cancel() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean closed = new AtomicBoolean(), resourceClosed = new AtomicBoolean();
    var fetch =
        (HlsPlaybackInput.Fetcher)
            (uri, r, p) ->
                new HlsPlaybackInput.Request() {
                  public HlsPlaybackInput.Resource open() throws IOException {
                    entered.countDown();
                    try {
                      release.await();
                    } catch (InterruptedException e) {
                      throw new IOException(e);
                    }
                    return new HlsPlaybackInput.Resource() {
                      public URI finalUri() {
                        return uri;
                      }

                      public InputStream body() {
                        throw new AssertionError("late resource consumed");
                      }

                      public void close() {
                        resourceClosed.set(true);
                      }
                    };
                  }

                  public void close() {
                    closed.set(true);
                    release.countDown();
                  }
                };
    var input = new HlsPlaybackInput(u("https://example.com/list"), true, null, fetch);
    var executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> result =
          executor.submit(
              () -> {
                try {
                  input.next();
                  throw new AssertionError();
                } catch (IOException expected) {
                }
              });
      yes(entered.await(2, TimeUnit.SECONDS));
      input.close();
      result.get(2, TimeUnit.SECONDS);
      yes(closed.get() && resourceClosed.get());
      bad(() -> input.next());
    } finally {
      release.countDown();
      executor.shutdownNow();
      input.close();
    }
    MemoryFetch live = new MemoryFetch();
    live.put("list", "#EXTM3U\n#EXT-X-TARGETDURATION:86400\n#EXTINF:1,\na\n");
    live.put("a", "A");
    var waiting = new HlsPlaybackInput(u("https://example.com/list"), true, null, live);
    waiting.next();
    var pool = Executors.newSingleThreadExecutor();
    try {
      var result =
          pool.submit(
              () -> {
                try {
                  waiting.next();
                  throw new AssertionError();
                } catch (IOException expected) {
                }
              });
      Thread.sleep(25);
      waiting.close();
      result.get(2, TimeUnit.SECONDS);
      yes(true);
    } finally {
      waiting.close();
      pool.shutdownNow();
    }
  }

  @Test
  void icyStreamBoundaries() throws Exception {
    icy();
  }

  @Test
  void playlistReferencesAndTrust() throws Exception {
    playlists();
  }

  @Test
  void hlsSelectionEncryptionAndEpochs() throws Exception {
    hls();
  }

  @Test
  void cancellationAndLateOpen() throws Exception {
    cancel();
  }
}
