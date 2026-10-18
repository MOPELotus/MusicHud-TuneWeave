package indi.mopelotus.musichud.audio.codec;

import java.io.*;
import java.util.*;

/**
 * Selects the first supported audio logical stream in each sequential Ogg group. Use {@link
 * JavaCodecs#oggLinks(InputStream)} for an owned physical stream. Initial discovery is incremental.
 * One reader/control thread; concurrent close cancels IO. Closing a Link releases that logical
 * decoder, without cancelling the physical source. Unknown link end offsets are -1 until its
 * selected EOS has arrived. A chain's PCM is consumed separately, so rate/channel changes are never
 * hidden. This controller owns the physical source; each decoder owns only its logical view.
 */
public final class OggLinks implements Closeable {
  @FunctionalInterface
  interface Factory {
    PcmDecoder open(InputStream logicalOwned) throws IOException;
  }

  private final Factory factory;
  private final Object readLock = new Object();
  private long physicalPosition;
  private final java.util.concurrent.atomic.AtomicBoolean physicalClosed =
      new java.util.concurrent.atomic.AtomicBoolean();
  private final InputStream input;
  private final Map<Integer, State> streams = new HashMap<>();
  private boolean bosWindow = true;
  private volatile boolean closed;
  private int group = -1, chainNumber;
  private Link current;
  private static final int[] CRC = crcTable();

  private record State(int sequence, boolean continued) {}

  private record Page(
      byte[] raw, int serial, boolean bos, boolean eos, int group, int bodyOffset, long offset) {}

  /** Fixed-format logical audio; metadata is sampled dynamically as physical pages arrive. */
  public final class Link implements PcmDecoder {
    private final int number, group, serial;
    private final View view;
    private final PcmDecoder decoder;
    private volatile boolean linkClosed;
    private boolean pcmEnded;
    private final PcmFormat format;
    private final String codec;
    private volatile long delivered;

    Link(int number, Page first, View view, PcmDecoder decoder, String codec) {
      this.number = number;
      this.codec = codec;
      group = first.group;
      serial = first.serial;
      this.view = view;
      this.decoder = decoder;
      format = Objects.requireNonNull(decoder.format());
    }

    /** Canonical codec from the selected, validated BOS header: "opus" or "vorbis". */
    public String codec() {
      return codec;
    }

    public int number() {
      return number;
    }

    public int group() {
      return group;
    }

    public int serial() {
      return serial;
    }

    /** Selected BOS page offset; this is not a verified complete physical-group index. */
    public long startOffset() {
      return view.startOffset;
    }

    /** Exclusive selected EOS page offset, or -1 until read. Foreign streams may be interleaved. */
    public long endOffset() {
      return view.endOffset;
    }

    public PcmFormat format() {
      return format;
    }

    public long framePosition() {
      return delivered;
    }

    public int readFrames(byte[] b, int off, int frames) throws IOException {
      synchronized (readLock) {
        check();
        if (linkClosed) throw new IOException("Ogg link closed");
        try {
          int n = decoder.readFrames(b, off, frames);
          if (n < 0) pcmEnded = true;
          check();
          if (linkClosed) throw new IOException("Late Ogg link PCM");
          if (n > 0) delivered += n;
          return n;
        } catch (IOException | RuntimeException e) {
          closePhysical(e);
          throw e;
        }
      }
    }

    public void close() throws IOException {
      synchronized (readLock) {
        if (linkClosed) return;
        linkClosed = true;
        decoder.close();
      }
    }
  }

  OggLinks(InputStream source, Factory factory) {
    input = Objects.requireNonNull(source);
    this.factory = Objects.requireNonNull(factory);
  }

  private void check() throws IOException {
    if (closed) throw new IOException("Ogg chain controller closed");
  }

  private void closePhysical(Throwable cause) {
    closed = true;
    if (physicalClosed.compareAndSet(false, true))
      try {
        input.close();
      } catch (IOException e) {
        if (cause != null) cause.addSuppressed(e);
      }
  }

  private static long le(byte[] b, int o, int n) {
    long x = 0;
    for (int i = 0; i < n; i++) x |= (long) (b[o + i] & 255) << (8 * i);
    return x;
  }

  private static int[] crcTable() {
    int[] a = new int[256];
    for (int i = 0; i < 256; i++) {
      int x = i << 24;
      for (int j = 0; j < 8; j++) x = (x << 1) ^ ((x < 0) ? 0x04c11db7 : 0);
      a[i] = x;
    }
    return a;
  }

  private static int crc(byte[] b) {
    int x = 0;
    for (int i = 0; i < b.length; i++) {
      int v = i >= 22 && i < 26 ? 0 : b[i] & 255;
      x = (x << 8) ^ CRC[((x >>> 24) ^ v) & 255];
    }
    return x;
  }

  private byte[] exact(int n) throws IOException {
    byte[] b = new byte[n];
    int p = 0, zeros = 0;
    while (p < n) {
      check();
      int k = input.read(b, p, n - p);
      check();
      if (k < 0) throw new EOFException("Truncated Ogg page");
      if (k == 0) {
        if (++zeros > 32) throw new IOException("Ogg input made no progress");
      } else {
        p += k;
        physicalPosition += k;
        zeros = 0;
      }
    }
    return b;
  }

  private Page readPage() throws IOException {
    if (closed) throw new IOException("Controller closed");
    long pageOffset = physicalPosition;
    int first = input.read();
    check();
    if (first >= 0) physicalPosition++;
    if (first < 0) {
      if (!streams.isEmpty()) throw new EOFException("Ogg stream without EOS");
      return null;
    }
    byte[] h = new byte[27];
    h[0] = (byte) first;
    System.arraycopy(exact(26), 0, h, 1, 26);
    if (h[0] != 'O' || h[1] != 'g' || h[2] != 'g' || h[3] != 'S' || h[4] != 0)
      throw new IOException("Invalid Ogg header");
    int flags = h[5] & 255;
    if ((flags & ~7) != 0) throw new IOException("Reserved Ogg flags");
    byte[] lace = exact(h[26] & 255);
    int n = 0;
    for (byte b : lace) n += b & 255;
    byte[] body = exact(n);
    byte[] raw = new byte[27 + lace.length + n];
    System.arraycopy(h, 0, raw, 0, 27);
    System.arraycopy(lace, 0, raw, 27, lace.length);
    System.arraycopy(body, 0, raw, 27 + lace.length, n);
    if (crc(raw) != (int) le(h, 22, 4)) throw new IOException("Ogg checksum mismatch");
    int serial = (int) le(h, 14, 4), seq = (int) le(h, 18, 4);
    boolean bos = (flags & 2) != 0, eos = (flags & 4) != 0;
    State previous = streams.get(serial);
    if (bos) {
      if (streams.isEmpty()) {
        group++;
        bosWindow = true;
      }
      if (!bosWindow || previous != null || seq != 0 || (flags & 1) != 0)
        throw new IOException("Invalid Ogg BOS grouping");
      if (streams.size() >= 64) throw new IOException("Too many Ogg logical streams");
    } else {
      bosWindow = false;
      if (previous == null
          || seq != previous.sequence + 1
          || (((flags & 1) != 0) != previous.continued))
        throw new IOException("Invalid Ogg page continuity");
    }
    boolean continued = lace.length > 0 && (lace[lace.length - 1] & 255) == 255;
    if (eos) {
      if (continued) throw new IOException("Incomplete final Ogg packet");
      streams.remove(serial);
    } else streams.put(serial, new State(seq, continued));
    return new Page(raw, serial, bos, eos, group, 27 + lace.length, pageOffset);
  }

  private static String codec(Page p) {
    if (!p.bos) return null;
    byte[] b = p.raw;
    int o = p.bodyOffset;
    if (b.length - o >= 8
        && b[o] == 'O'
        && b[o + 1] == 'p'
        && b[o + 2] == 'u'
        && b[o + 3] == 's'
        && b[o + 4] == 'H'
        && b[o + 5] == 'e'
        && b[o + 6] == 'a'
        && b[o + 7] == 'd') return "opus";
    if (b.length - o >= 7
        && b[o] == 1
        && b[o + 1] == 'v'
        && b[o + 2] == 'o'
        && b[o + 3] == 'r'
        && b[o + 4] == 'b'
        && b[o + 5] == 'i'
        && b[o + 6] == 's') return "vorbis";
    return null;
  }

  /**
   * Exhaust/close previous chain before calling. Same-group alternate audio streams are ignored.
   */
  public Link nextLink() throws IOException {
    synchronized (readLock) {
      check();
      if (current != null && !current.pcmEnded)
        throw new IOException("Previous Ogg link must reach EOF or be explicitly discarded");
      try {
        return nextLinkLocked();
      } catch (IOException | RuntimeException e) {
        closePhysical(e);
        throw e;
      }
    }
  }

  /** Explicit bounded skip. Ordinary nextLink never silently discards an unread selected link. */
  public void discardCurrentLink() throws IOException {
    synchronized (readLock) {
      check();
      if (current == null) return;
      try {
        current.close();
        int pages = 0;
        long start = physicalPosition;
        while (!current.view.last) {
          if (++pages > 4096 || physicalPosition - start > 16L * 1024 * 1024)
            throw new IOException("Ogg discard scan budget");
          Page p = readPage();
          if (p == null || p.group != current.group)
            throw new EOFException("Missing selected Ogg EOS");
          if (p.serial == current.serial && p.eos) {
            current.view.last = true;
            current.view.endOffset = p.offset + p.raw.length;
          }
        }
        current.pcmEnded = true;
      } catch (IOException | RuntimeException e) {
        closePhysical(e);
        throw e;
      }
    }
  }

  private Link nextLinkLocked() throws IOException {
    if (current != null) {
      current.close();
      int oldGroup = current.group;
      current = null;
      int skipped = 0;
      long drainStart = physicalPosition;
      while (!streams.isEmpty() && group == oldGroup) {
        if (++skipped > 4096 || physicalPosition - drainStart > 16L * 1024 * 1024)
          throw new IOException("Ogg ignored-stream drain budget");
        Page p = readPage();
        if (p == null) break;
      }
    }
    int searched = 0;
    long searchStart = physicalPosition;
    Page p;
    while ((p = readPage()) != null) {
      if (++searched > 4096 || physicalPosition - searchStart > 16L * 1024 * 1024)
        throw new IOException("Ogg audio-selection scan budget");
      String codec = codec(p);
      if (codec == null) continue;
      View view = new View(p);
      PcmDecoder decoder = factory.open(view);
      try {
        check();
      } catch (IOException late) {
        try {
          decoder.close();
        } catch (IOException close) {
          late.addSuppressed(close);
        }
        throw late;
      }
      current = new Link(chainNumber++, p, view, decoder, codec);
      return current;
    }
    return null;
  }

  private final class View extends InputStream {
    final int selected, selectedGroup;
    final long startOffset;
    volatile long endOffset = -1;
    byte[] pending;
    int offset;
    boolean last, viewClosed;

    View(Page first) {
      selected = first.serial;
      selectedGroup = first.group;
      startOffset = first.offset;
      pending = first.raw;
      last = first.eos;
      if (last) endOffset = first.offset + first.raw.length;
    }

    public int read() throws IOException {
      byte[] b = new byte[1];
      return read(b, 0, 1) < 0 ? -1 : b[0] & 255;
    }

    public int read(byte[] b, int o, int n) throws IOException {
      Objects.checkFromIndexSize(o, n, b.length);
      check();
      if (n == 0) return 0;
      if (viewClosed) throw new IOException("Logical view closed");
      int skipped = 0;
      long skipStart = physicalPosition;
      while (offset == pending.length) {
        if (last) return -1;
        if (++skipped > 4096 || physicalPosition - skipStart > 16L * 1024 * 1024)
          throw new IOException("Ogg selected-view gap budget");
        Page p = readPage();
        if (p == null || p.group != selectedGroup)
          throw new EOFException("Selected logical stream missing EOS");
        if (p.serial != selected) continue;
        pending = p.raw;
        offset = 0;
        last = p.eos;
        if (last) endOffset = p.offset + p.raw.length;
      }
      int count = Math.min(n, pending.length - offset);
      System.arraycopy(pending, offset, b, o, count);
      offset += count;
      check();
      if (viewClosed) throw new IOException("Late logical bytes");
      return count;
    }

    public void close() {
      viewClosed = true;
    }
  }

  public void close() throws IOException {
    closed = true;
    IOException failure = null;
    if (physicalClosed.compareAndSet(false, true))
      try {
        input.close();
      } catch (IOException e) {
        failure = e;
      }
    synchronized (readLock) {
      try {
        if (current != null) current.close();
      } catch (IOException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
      streams.clear();
    }
    if (failure != null) throw failure;
  }
}
