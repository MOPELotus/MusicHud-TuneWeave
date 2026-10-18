package indi.mopelotus.musichud.audio.container.input;

import indi.mopelotus.musichud.audio.container.hls.HlsManifest;
import indi.mopelotus.musichud.audio.container.hls.HlsSession;
import java.io.*;
import java.net.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

/**
 * Synchronous bounded HLS connector. HlsManifest/HlsSession remain the only grammar/reload state.
 */
public final class HlsPlaybackInput implements AutoCloseable {
  /**
   * Open request owns cancellation from before network connect until close; open may finish after
   * close.
   */
  public interface Request extends AutoCloseable {
    Resource open() throws IOException;

    void close();
  }

  public interface Resource extends AutoCloseable {
    URI finalUri();

    InputStream body();

    void close() throws IOException;
  }

  @FunctionalInterface
  public interface Fetcher {
    Request request(URI uri, HlsManifest.Range range, boolean publicOnly) throws IOException;
  }

  @FunctionalInterface
  public interface Delay {
    void await(long millis) throws IOException;
  }

  public record SegmentData(
      HlsManifest.Segment segment,
      byte[] initialization,
      byte[] media,
      long discontinuitySequence,
      boolean resetContainer) {
    public SegmentData {
      initialization = initialization == null ? null : initialization.clone();
      media = media.clone();
    }

    public byte[] initialization() {
      return initialization == null ? null : initialization.clone();
    }

    public byte[] media() {
      return media.clone();
    }

    /**
     * Container adapter owns stream passed here; it must preserve TS packet PTS and per-segment
     * discontinuity.
     */
    public InputStream openMedia() {
      return new ByteArrayInputStream(media);
    }

    public InputStream openFragmentedMp4() {
      return initialization == null
          ? openMedia()
          : new SequenceInputStream(new ByteArrayInputStream(initialization), openMedia());
    }
  }

  private final Fetcher fetcher;
  private final boolean publicOnly;
  private final String language;
  private final Delay delay;
  private final HlsSession session = new HlsSession();
  private final ArrayDeque<HlsManifest.Segment> pending = new ArrayDeque<>();
  private URI playlist;
  private HlsManifest.Manifest manifest;
  private long discontinuity;
  private boolean first = true, closed, lastReloadChanged;
  private String initIdentity;
  private byte[] initBytes;
  private final Object lifecycle = new Object();

  public HlsPlaybackInput(URI root, boolean publicOnly, String language, Fetcher fetcher)
      throws IOException {
    this(root, publicOnly, language, fetcher, null);
  }

  public HlsPlaybackInput(
      URI root, boolean publicOnly, String language, Fetcher fetcher, Delay delay)
      throws IOException {
    this.fetcher = Objects.requireNonNull(fetcher);
    this.publicOnly = publicOnly;
    this.language = language;
    this.delay = delay;
    playlist = PlaylistResolver.validate(root, null, publicOnly);
  }

  public SegmentData next() throws IOException {
    check();
    while (pending.isEmpty()) {
      if (manifest != null && manifest.endList()) return null;
      if (manifest != null) {
        await(session.reloadDelayMillis(manifest, lastReloadChanged));
        check();
      }
      reload();
    }
    HlsManifest.Segment segment = pending.removeFirst();
    if (segment.discontinuity()) discontinuity++;
    String identity = initKey(segment.init());
    boolean initChanged =
        !Objects.equals(initIdentity, identity)
            || segment.discontinuity() && segment.init() != null;
    if (initChanged) {
      initBytes =
          segment.init() == null
              ? null
              : readEncrypted(
                  segment.init().uri(),
                  segment.init().range(),
                  segment.init().key(),
                  segment.sequence(),
                  4 * 1024 * 1024);
      initIdentity = identity;
    }
    byte[] media =
        readEncrypted(
            segment.uri(), segment.range(), segment.key(), segment.sequence(), 16 * 1024 * 1024);
    check();
    boolean reset = first || initChanged || segment.discontinuity();
    first = false;
    return new SegmentData(segment, initBytes, media, discontinuity, reset);
  }

  private void reload() throws IOException {
    Set<URI> visited = new HashSet<>();
    URI current = playlist;
    for (int depth = 0; depth <= 4; depth++) {
      if (!visited.add(current)) throw new IOException("HLS master cycle");
      Fetched fetched = fetch(current, null, 1048576);
      URI actual = fetched.uri;
      HlsManifest.Manifest parsed =
          HlsManifest.parse(actual, PlaylistResolver.decode(fetched.bytes));
      if (!parsed.variants().isEmpty()) {
        if (depth == 4) throw new IOException("HLS master depth limit");
        var variant =
            parsed.variants().stream()
                .max(Comparator.comparingLong(HlsManifest.Variant::bandwidth))
                .orElseThrow();
        URI selected = variant.uri();
        if (variant.audioGroup() != null) {
          var audio = session.selectAudio(parsed, variant.audioGroup(), language);
          if (audio.uri() != null) selected = audio.uri();
        }
        current = PlaylistResolver.validate(selected, actual, publicOnly);
        continue;
      }
      playlist = actual;
      long token = session.generation();
      List<HlsManifest.Segment> fresh = session.reload(token, parsed);
      // Compute discontinuity count at the first fresh segment, including already-consumed overlap
      // in a live window.
      if (!fresh.isEmpty()) {
        long base = parsed.discontinuitySequence();
        for (var s : parsed.segments()) {
          if (s.sequence() == fresh.getFirst().sequence()) break;
          if (s.discontinuity()) base++;
        }
        discontinuity = base;
      }
      pending.addAll(fresh);
      lastReloadChanged = !fresh.isEmpty();
      manifest = parsed;
      return;
    }
  }

  private byte[] readEncrypted(
      URI uri, HlsManifest.Range range, HlsManifest.Key key, long sequence, int max)
      throws IOException {
    byte[] data = fetch(uri, range, max).bytes;
    if (key == null) return data;
    byte[] secret = fetch(key.uri(), null, 16).bytes;
    if (secret.length != 16) throw new IOException("AES128 key must be 16 bytes");
    byte[] iv = key.explicitIv();
    if (iv == null) iv = java.nio.ByteBuffer.allocate(16).putLong(0).putLong(sequence).array();
    try {
      Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
      cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(iv));
      byte[] plain = cipher.doFinal(data);
      if (plain.length > max) throw new IOException("Decrypted segment too large");
      return plain;
    } catch (java.security.GeneralSecurityException e) {
      throw new IOException("Invalid encrypted HLS segment", e);
    } finally {
      Arrays.fill(secret, (byte) 0);
    }
  }

  private record Fetched(URI uri, byte[] bytes) {}

  private Fetched fetch(URI uri, HlsManifest.Range range, int max) throws IOException {
    check();
    URI requested = PlaylistResolver.validate(uri, playlist, publicOnly);
    long token = session.generation();
    Request request = fetcher.request(requested, range, publicOnly);
    session.attach(token, request);
    try {
      Resource resource = request.open();
      try (resource) {
        check();
        if (session.generation() != token) throw new IOException("Stale HLS open");
        URI actual = PlaylistResolver.validate(resource.finalUri(), requested, publicOnly);
        byte[] bytes = resource.body().readNBytes(max + 1);
        check();
        if (bytes.length > max) throw new IOException("HLS resource exceeds limit");
        if (range != null && bytes.length != range.length())
          throw new IOException("Incomplete HLS byte range");
        return new Fetched(actual, bytes);
      }
    } finally {
      session.detach(request);
      request.close();
    }
  }

  private static String initKey(HlsManifest.Init init) {
    if (init == null) return null;
    var key = init.key();
    return init.uri()
        + "|"
        + init.range()
        + "|"
        + (key == null ? "" : key.uri() + "|" + Arrays.toString(key.explicitIv()));
  }

  private void check() throws IOException {
    session.generation();
  }

  private void await(long millis) throws IOException {
    if (delay != null) {
      delay.await(millis);
      return;
    }
    long end = System.nanoTime() + millis * 1000000L;
    synchronized (lifecycle) {
      while (!closed) {
        long left = end - System.nanoTime();
        if (left <= 0) return;
        try {
          lifecycle.wait(Math.max(1, left / 1000000));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new InterruptedIOException("HLS interrupted");
        }
      }
    }
    check();
  }

  /** close is concurrent with next; state mutation/next itself stays single-reader. */
  @Override
  public void close() {
    session.close();
    synchronized (lifecycle) {
      closed = true;
      lifecycle.notifyAll();
    }
  }
}
