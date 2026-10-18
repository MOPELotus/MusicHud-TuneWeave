package indi.mopelotus.musichud.audio.codec;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.*;
import indi.mopelotus.musichud.audio.codec.internal.OpusPacketDecoder;
import indi.mopelotus.musichud.audio.codec.internal.VorbisPacketDecoder;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.concentus.*;

/**
 * One immutable physical Ogg link, sparse page indexing and exact codec preroll. HTTP
 * identity/snapshot policy stays in the channel owner. Uses the shared raw packet cores. A source
 * represents one stable physical link; chained links require their chain controller. Each seek
 * supersedes the previous result. Result decoder positions count frames from result.sourceFrame(),
 * so callers apply that source offset once. Close replaced result decoders to release codec state.
 */
public final class OggSeekDecoder implements AutoCloseable {
  @FunctionalInterface
  public interface Source {
    SeekableByteChannel open() throws IOException;
  }

  public record Statistics(
      long generation,
      long bytesRead,
      int indexProbes,
      long decodedPrerollFrames,
      long elapsedNanos) {}

  public record Result(
      PcmDecoder decoder, long sourceFrame, long totalSourceFrames, Statistics statistics) {}

  private record Page(
      long offset,
      int serial,
      int sequence,
      int flags,
      long granule,
      byte[] lace,
      byte[] body,
      int bytes) {}

  private record Point(long offset, int bytes, long granule, int flags, int sequence) {}

  private record Tagged(byte[] data, long start, long end, int samples) {}

  private static final int PAGE_MAX = 65307, WINDOW = 65536, MAX_PROBES = 12;
  private static final int[] CRC = table();
  private final Source source;
  private final long length;
  private final int serial;
  private final byte[][] headers;
  private final boolean isOpus;
  private final int rate, channels, preSkip, opusPreroll;
  private final long totalGranule, granuleOrigin;
  private final TreeMap<Long, Point> points = new TreeMap<>();
  private final AtomicReference<Operation> active = new AtomicReference<>();
  private final AtomicLong generations = new AtomicLong();
  private volatile boolean closed;

  private final class Operation implements Closeable {
    final long generation = generations.incrementAndGet();
    volatile boolean cancelled;
    SeekableByteChannel channel;
    long bytes;
    int probes, pages, progressPages;
    long progressBytes;
    boolean streaming;
    final long byteBudget;

    Operation(long byteBudget) {
      this.byteBudget = byteBudget;
    }

    void check() throws IOException {
      if (cancelled || closed || active.get() != this)
        throw new IOException("Stale or cancelled Ogg seek");
    }

    SeekableByteChannel attach() throws IOException {
      synchronized (this) {
        check();
        if (channel != null) return channel;
      }
      SeekableByteChannel opened = source.open();
      synchronized (this) {
        if (cancelled || closed || active.get() != this) {
          opened.close();
          throw new IOException("Late Ogg channel open");
        }
        if (channel == null) channel = opened;
        else opened.close();
        return channel;
      }
    }

    byte[] readAt(long offset, int amount) throws IOException {
      check();
      if (offset < 0 || offset > length || amount < 0)
        throw new IOException("Ogg offset outside source");
      int requested = (int) Math.min(amount, length - offset);
      if (requested > byteBudget - (bytes - (streaming ? progressBytes : 0)))
        throw new IOException("Ogg seek operation byte budget exceeded");
      SeekableByteChannel in = attach();
      in.position(offset);
      ByteBuffer b = ByteBuffer.allocate(requested);
      int zeroReads = 0;
      while (b.hasRemaining()) {
        check();
        int n = in.read(b);
        if (n < 0) break;
        if (n == 0) {
          if (++zeroReads > 32) throw new IOException("Ogg source made no progress");
          Thread.onSpinWait();
        } else {
          zeroReads = 0;
          bytes += n;
        }
      }
      check();
      return Arrays.copyOf(b.array(), b.position());
    }

    void beginPlayback() {
      streaming = true;
      progressBytes = bytes;
      progressPages = pages;
    }

    void progress() {
      if (streaming) {
        progressBytes = bytes;
        progressPages = pages;
      }
    }

    public void close() throws IOException {
      cancelled = true;
      SeekableByteChannel old;
      synchronized (this) {
        old = channel;
        channel = null;
      }
      if (old != null) old.close();
    }
  }

  private static int[] table() {
    int[] t = new int[256];
    for (int i = 0; i < 256; i++) {
      int n = i << 24;
      for (int j = 0; j < 8; j++) n = (n << 1) ^ ((n < 0) ? 0x04c11db7 : 0);
      t[i] = n;
    }
    return t;
  }

  private static long le(byte[] b, int p, int n) {
    long x = 0;
    for (int i = 0; i < n; i++) x |= (long) (b[p + i] & 255) << (8 * i);
    return x;
  }

  private static boolean magic(byte[] b, int p) {
    return p + 27 <= b.length
        && b[p] == 'O'
        && b[p + 1] == 'g'
        && b[p + 2] == 'g'
        && b[p + 3] == 'S'
        && b[p + 4] == 0;
  }

  private static boolean starts(byte[] b, String value) {
    byte[] s = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    if (b.length < s.length) return false;
    for (int i = 0; i < s.length; i++) if (b[i] != s[i]) return false;
    return true;
  }

  private static Page page(byte[] b, int p, long base) throws IOException {
    if (!magic(b, p) || (b[p + 5] & ~7) != 0) throw new IOException("Invalid Ogg page");
    int n = b[p + 26] & 255;
    if (p + 27 + n > b.length) throw new EOFException("Truncated Ogg lacing");
    int size = 27 + n;
    for (int i = 0; i < n; i++) size += b[p + 27 + i] & 255;
    if (p + size > b.length) throw new EOFException("Truncated Ogg page");
    int crc = 0;
    for (int i = 0; i < size; i++) {
      int v = i >= 22 && i < 26 ? 0 : b[p + i] & 255;
      crc = (crc << 8) ^ CRC[((crc >>> 24) ^ v) & 255];
    }
    if (crc != (int) le(b, p + 22, 4)) throw new IOException("Ogg CRC mismatch");
    return new Page(
        base + p,
        (int) le(b, p + 14, 4),
        (int) le(b, p + 18, 4),
        b[p + 5] & 255,
        le(b, p + 6, 8),
        Arrays.copyOfRange(b, p + 27, p + 27 + n),
        Arrays.copyOfRange(b, p + 27 + n, p + size),
        size);
  }

  private Page readPage(Operation op, long offset) throws IOException {
    if (++op.pages - (op.streaming ? op.progressPages : 0) > 4096)
      throw new IOException("Ogg page scan budget exceeded");
    byte[] head = op.readAt(offset, 27);
    if (head.length < 27) throw new EOFException("Missing Ogg page");
    int n = head[26] & 255;
    byte[] lace = op.readAt(offset + 27, n);
    if (lace.length != n) throw new EOFException("Truncated Ogg lacing");
    int size = 27 + n;
    for (byte b : lace) size += b & 255;
    byte[] whole = op.readAt(offset, size);
    return page(whole, 0, offset);
  }

  private List<Point> scan(Operation op, long start, int width) throws IOException {
    byte[] b = op.readAt(start, width + PAGE_MAX - 1);
    var list = new ArrayList<Point>();
    for (int p = 0; p < Math.min(width, b.length - 26); p++) {
      if (!magic(b, p)) continue;
      try {
        Page pg = page(b, p, start);
        if (pg.serial == serial && (pg.granule >= 0 || (pg.flags & 4) != 0))
          list.add(new Point(pg.offset, pg.bytes, pg.granule, pg.flags, pg.sequence));
        p += pg.bytes - 1;
      } catch (IOException invalidCandidate) {
      }
    }
    op.check();
    synchronized (points) {
      for (Point p : list) points.put(p.offset, p);
      if (points.size() > 4096) {
        var keep = new TreeMap<Long, Point>();
        int i = 0;
        for (var e : points.entrySet())
          if ((i++ & 1) == 0 || (e.getValue().flags & 6) != 0) keep.put(e.getKey(), e.getValue());
        points.clear();
        points.putAll(keep);
      }
    }
    return list;
  }

  /** Source length and entity identity must be stable for the lifetime of this object. */
  public OggSeekDecoder(Source source, long length) throws IOException {
    this(source, length, 48000);
  }

  public OggSeekDecoder(Source source, long length, int opusPreroll) throws IOException {
    if (opusPreroll < 3840 || opusPreroll > 480000)
      throw new IllegalArgumentException("Opus preroll outside80ms–10s");
    this.opusPreroll = opusPreroll;
    this.source = Objects.requireNonNull(source);
    if (length < 27) throw new IOException("Ogg source too short");
    this.length = length;
    Operation op = new Operation(64L * 1024 * 1024);
    active.set(op);
    byte[][] found;
    int chosen;
    boolean opus;
    int sampleRate, ch, skip;
    try {
      long offset = 0;
      Page first = readPage(op, offset);
      if ((first.flags & 2) == 0 || first.sequence != 0) throw new IOException("Missing Ogg BOS");
      chosen = first.serial;
      boolean selectedAudio = false;
      var packetList = new ArrayList<byte[]>();
      var partial = new ByteArrayOutputStream();
      int sequence = -1;
      boolean previousContinued = false;
      boolean detected = false;
      while (packetList.size() < 3) {
        Page p = offset == 0 ? first : readPage(op, offset);
        offset += p.bytes;
        if (offset > 16L * 1024 * 1024) throw new IOException("Ogg headers exceed bound");
        if (!selectedAudio) {
          boolean supported =
              (p.flags & 2) != 0
                  && (starts(p.body, "OpusHead")
                      || (p.body.length >= 7
                          && p.body[0] == 1
                          && starts(Arrays.copyOfRange(p.body, 1, p.body.length), "vorbis")));
          if (!supported) continue;
          chosen = p.serial;
          selectedAudio = true;
        }
        if (p.serial != chosen) continue;
        if (p.sequence != ++sequence || (((p.flags & 1) != 0) != previousContinued))
          throw new IOException("Ogg header continuity");
        int at = 0;
        for (byte l : p.lace) {
          int n = l & 255;
          if (partial.size() + n > 16 * 1024 * 1024) throw new IOException("Ogg packet bound");
          partial.write(p.body, at, n);
          at += n;
          if (n < 255) {
            packetList.add(partial.toByteArray());
            partial.reset();
          }
        }
        previousContinued = partial.size() != 0;
        if (!packetList.isEmpty() && !detected) {
          byte[] h = packetList.get(0);
          detected = true;
          if (!starts(h, "OpusHead")
              && !(h.length >= 7
                  && h[0] == 1
                  && starts(Arrays.copyOfRange(h, 1, h.length), "vorbis")))
            throw new IOException("First Ogg logical stream not supported audio");
        }
        if (detected && starts(packetList.get(0), "OpusHead") && packetList.size() >= 2) break;
      }
      opus = starts(packetList.get(0), "OpusHead");
      found = packetList.subList(0, opus ? 2 : 3).toArray(byte[][]::new);
      if (opus) {
        byte[] h = found[0];
        validateOpusHeaders(h, found[1]);
        ch = h[9] & 255;
        sampleRate = 48000;
        skip = (int) le(h, 10, 2);
      } else {
        Info info = vorbisInfo(found);
        ch = info.channels;
        sampleRate = info.rate;
        skip = 0;
        info.clear();
      }
      if (ch < 1 || ch > 8 || sampleRate < 1 || sampleRate > 384000)
        throw new IOException("Invalid PCM dimensions");
    } catch (Exception e) {
      op.close();
      active.compareAndSet(op, null);
      if (e instanceof IOException x) throw x;
      throw new IOException("Invalid Ogg headers", e);
    }
    headers = found;
    serial = chosen;
    isOpus = opus;
    rate = sampleRate;
    channels = ch;
    preSkip = skip;
    try {
      PacketCursor originCursor =
          new PacketCursor(op, new Point(0, 0, 0, 2, 0), isOpus ? null : vorbisInfo(headers));
      Tagged firstAudio = originCursor.next();
      if (firstAudio == null) throw new IOException("Empty Ogg audio link");
      granuleOrigin = firstAudio.start;
      if (originCursor.info != null) originCursor.info.clear();
      scan(op, 0, (int) Math.min(WINDOW, length));
      List<Point> tail =
          scan(op, Math.max(0, length - 2L * PAGE_MAX), (int) Math.min(2L * PAGE_MAX, length));
      Point eos = null;
      for (Point p : tail)
        if ((p.flags & 4) != 0 && (eos == null || p.offset > eos.offset)) eos = p;
      if (eos == null)
        throw new IOException(
            "Cannot find selected Ogg link EOS in bounded tail; supply a single physical link");
      Point predecessor = null;
      synchronized (points) {
        for (Point p : points.values())
          if (p.offset < eos.offset
              && (p.granule > granuleOrigin || (p.flags & 2) != 0)
              && (predecessor == null || p.offset > predecessor.offset)) predecessor = p;
      }
      if (predecessor == null) throw new IOException("Missing Ogg marked page before EOS");
      totalGranule = eos.granule < 0 ? predecessor.granule : eos.granule;
      if (totalGranule < preSkip + granuleOrigin)
        throw new IOException("Invalid Ogg final granule");
      PacketCursor validateEnd =
          new PacketCursor(op, predecessor, isOpus ? null : vorbisInfo(headers));
      while (validateEnd.next() != null) {}
      if (validateEnd.info != null) validateEnd.info.clear();
    } finally {
      op.close();
      active.compareAndSet(op, null);
    }
  }

  private static void validateOpusHeaders(byte[] head, byte[] tags) throws IOException {
    try (var validation = new OpusPacketDecoder(head)) {
      /* Reuse the codec's canonical mapping guard. */
    }
    if (!starts(tags, "OpusTags") || tags.length < 16) throw new IOException("Missing OpusTags");
    long vendor = le(tags, 8, 4), position = 12 + vendor;
    if (position + 4 > tags.length) throw new IOException("Truncated OpusTags vendor");
    long count = le(tags, (int) position, 4);
    position += 4;
    if (count > (tags.length - position) / 4) throw new IOException("Invalid OpusTags count");
    for (long i = 0; i < count; i++) {
      if (position + 4 > tags.length) throw new IOException("Truncated OpusTags length");
      long n = le(tags, (int) position, 4);
      position += 4 + n;
      if (position > tags.length) throw new IOException("Truncated OpusTags value");
    }
  }

  private static Packet packet(byte[] data, int number) {
    Packet p = new Packet();
    p.packet_base = data;
    p.bytes = data.length;
    p.packetno = number;
    p.b_o_s = number == 0 ? 1 : 0;
    p.granulepos = -1;
    return p;
  }

  private static Info vorbisInfo(byte[][] headers) throws IOException {
    Info i = new Info();
    i.init();
    Comment c = new Comment();
    c.init();
    for (int n = 0; n < 3; n++)
      if (i.synthesis_headerin(c, packet(headers[n], n)) != 0)
        throw new IOException("Invalid Vorbis headers");
    return i;
  }

  public long totalFrames() {
    return totalGranule - preSkip - granuleOrigin;
  }

  public PcmFormat format() {
    return new PcmFormat(
        rate,
        channels,
        PcmFormat.Encoding.S16LE,
        channels == 1
            ? PcmFormat.Layout.MONO
            : channels == 2 ? PcmFormat.Layout.STEREO : PcmFormat.Layout.VORBIS);
  }

  private Point locate(Operation op, long threshold) throws IOException {
    var attempted = new ArrayList<Long>();
    for (int attempt = 0; attempt < MAX_PROBES; attempt++) {
      Point low = null, high = null;
      synchronized (points) {
        for (Point p : points.values()) {
          if (p.granule < 0) continue;
          if (p.granule <= threshold && (low == null || p.granule > low.granule)) low = p;
          if (p.granule > threshold && (high == null || p.granule < high.granule)) high = p;
        }
      }
      if (low == null || high == null) throw new IOException("Ogg seek timestamp not bracketed");
      if (high.offset - low.offset <= WINDOW) {
        return low;
      }
      double fraction = (double) (threshold - low.granule) / (high.granule - low.granule);
      long guess = low.offset + (long) ((high.offset - low.offset) * fraction);
      long start = Math.max(low.offset, Math.min(high.offset - WINDOW / 2, guess - WINDOW / 2));
      long preferred = start;
      boolean unused = false;
      for (int distance = 0; distance < MAX_PROBES && !unused; distance++)
        for (int sign : new int[] {1, -1}) {
          long candidate =
              Math.max(
                  low.offset,
                  Math.min(high.offset - WINDOW / 2, preferred + (long) distance * WINDOW * sign));
          boolean overlaps = false;
          for (long prior : attempted)
            if (Math.abs(prior - candidate) < WINDOW / 2) {
              overlaps = true;
              break;
            }
          if (!overlaps) {
            start = candidate;
            unused = true;
            break;
          }
        }
      if (!unused) throw new IOException("Ogg index could not find an unprobed bounded window");
      attempted.add(start);
      List<Point> window = scan(op, start, WINDOW);
      op.probes++;
      Point before = null, after = null;
      for (Point p : window) {
        if (p.granule <= threshold) before = p;
        else if (after == null) after = p;
      }
      if (before != null && after != null) return before;
    }
    throw new IOException("Ogg index search budget exhausted");
  }

  public Result seek(long sourceFrame) throws IOException {
    if (sourceFrame < 0) throw new IllegalArgumentException("Negative seek");
    if (closed) throw new IOException("Ogg seeker closed");
    long start = System.nanoTime();
    Operation op = new Operation(16L * 1024 * 1024);
    Operation previous = active.getAndSet(op);
    if (previous != null) previous.close();
    try {
      long target = Math.min(sourceFrame, totalFrames()),
          granule = target + preSkip + granuleOrigin;
      Point anchor;
      if (target < rate * 2L) {
        synchronized (points) {
          anchor = points.firstEntry().getValue();
        }
      } else anchor = locate(op, Math.max(0, granule - (isOpus ? opusPreroll : 8192)));
      Reader reader = new Reader(op, anchor, target);
      op.check();
      op.beginPlayback();
      return new Result(
          reader,
          target,
          totalFrames(),
          new Statistics(
              op.generation, op.bytes, op.probes, reader.preroll, System.nanoTime() - start));
    } catch (Exception e) {
      op.close();
      active.compareAndSet(op, null);
      if (e instanceof IOException x) throw x;
      throw new IOException("Ogg seek failed", e);
    }
  }

  private final class PacketCursor {
    final Operation op;
    long offset;
    int sequence;
    boolean first = true, skipLeading;
    int headersToSkip;
    final ByteArrayOutputStream partial = new ByteArrayOutputStream();
    final ArrayDeque<Tagged> ready = new ArrayDeque<>();
    final Info info;
    final boolean fromBos;
    long previousEnd = -1;
    int previousBlock = -1;
    boolean ended;

    PacketCursor(Operation op, Point anchor, Info info) {
      this.op = op;
      offset = anchor.offset;
      sequence = anchor.sequence - 1;
      this.info = info;
      fromBos = (anchor.flags & 2) != 0;
      headersToSkip = fromBos ? headers.length : 0;
    }

    Tagged next() throws IOException {
      while (ready.isEmpty() && !ended) load();
      return ready.poll();
    }

    void load() throws IOException {
      Page p = readPage(op, offset);
      offset += p.bytes;
      if (p.serial != serial) return;
      if (p.sequence != ++sequence) throw new IOException("Ogg gap at seek resume");
      boolean continued = (p.flags & 1) != 0;
      if (first) {
        skipLeading = continued;
        first = false;
      } else if (continued != (skipLeading || partial.size() != 0))
        throw new IOException("Ogg continued packet mismatch");
      var data = new ArrayList<byte[]>();
      int at = 0;
      for (byte l : p.lace) {
        int n = l & 255;
        if (!skipLeading) {
          if (partial.size() + n > 16 * 1024 * 1024) throw new IOException("Ogg packet bound");
          partial.write(p.body, at, n);
        }
        at += n;
        if (n < 255) {
          if (skipLeading) skipLeading = false;
          else {
            byte[] d = partial.toByteArray();
            partial.reset();
            if (headersToSkip > 0) headersToSkip--;
            else data.add(d);
          }
        }
      }
      if ((p.flags & 4) != 0) {
        if (partial.size() != 0 || skipLeading) throw new IOException("Truncated Ogg final packet");
        ended = true;
      }
      if (data.isEmpty()) {
        if (ended && previousEnd >= 0 && p.granule >= 0 && p.granule != previousEnd)
          throw new IOException("Empty Ogg EOS granule mismatch");
        return;
      }
      if (p.granule < 0) throw new IOException("Missing completed-packet granule");
      int[] sizes = new int[data.size()], durations = new int[data.size()];
      long sum = 0;
      int prior = previousBlock;
      for (int i = 0; i < data.size(); i++) {
        if (isOpus) {
          sizes[i] = OpusPacketInfo.getNumSamples(data.get(i), 0, data.get(i).length, 48000);
          if (sizes[i] < 1 || sizes[i] > 5760)
            throw new IOException("Invalid Opus packet duration");
          durations[i] = sizes[i];
        } else {
          sizes[i] = info.blocksize(packet(data.get(i), 3 + i));
          if (sizes[i] < 1) throw new IOException("Invalid Vorbis mode");
          durations[i] = prior < 0 ? 0 : (prior + sizes[i]) / 4;
          prior = sizes[i];
        }
        sum += durations[i];
      }
      long cursor = previousEnd;
      if (cursor < 0) {
        if ((p.flags & 4) != 0) {
          if (fromBos) cursor = Math.max(0, p.granule - sum);
          else throw new IOException("Cannot infer EOS-only resume position");
        } else cursor = p.granule - sum;
      }
      for (int i = 0; i < data.size(); i++) {
        long end = cursor + durations[i];
        ready.add(new Tagged(data.get(i), cursor, end, durations[i]));
        cursor = end;
      }
      if ((p.flags & 4) == 0 && cursor != p.granule)
        throw new IOException("Ogg granule discontinuity");
      if ((p.flags & 4) != 0 && (p.granule > cursor || previousEnd >= 0 && p.granule < previousEnd))
        throw new IOException("Ogg EOS granule outside decoded samples");
      previousEnd = cursor;
      previousBlock = prior;
    }
  }

  private byte[] vorbisConfiguration() {
    ByteArrayOutputStream config = new ByteArrayOutputStream();
    config.write(2);
    for (int n = 0; n < 2; n++) {
      int size = headers[n].length;
      while (size >= 255) {
        config.write(255);
        size -= 255;
      }
      config.write(size);
    }
    for (byte[] header : headers) config.writeBytes(header);
    return config.toByteArray();
  }

  private final class Reader implements PcmDecoder {
    final Operation op;
    final PacketCursor packets;
    final OpusPacketDecoder opus;
    final VorbisPacketDecoder vorbis;
    final Info info;
    final long target;
    final Object publication = new Object();
    volatile long delivered;
    long preroll;
    byte[] pending;
    int pendingOffset;
    boolean first = true, reading, codecReleased;
    volatile boolean readerClosed;

    Reader(Operation op, Point anchor, long target) throws IOException {
      this.op = op;
      this.target = target;
      opus = isOpus ? new OpusPacketDecoder(headers[0]) : null;
      vorbis = isOpus ? null : new VorbisPacketDecoder(vorbisConfiguration());
      info = isOpus ? null : vorbisInfo(headers);
      packets = new PacketCursor(op, anchor, info);
      try {
        long desired = target + preSkip + granuleOrigin - (isOpus ? opusPreroll : 8192);
        var preview = new ArrayList<Tagged>();
        long previewBytes = 0;
        Tagged t;
        while ((t = packets.next()) != null) {
          previewBytes += t.data.length;
          if (previewBytes > 4L * 1024 * 1024)
            throw new IOException("Ogg seek preview byte budget exceeded");
          preview.add(t);
          if (t.end >= target + preSkip + granuleOrigin) break;
          if (preview.size() > 4096) throw new IOException("Ogg preroll packet budget");
        }
        int chosen = 0;
        for (int i = 0; i < preview.size(); i++) {
          Tagged p = preview.get(i);
          if ((isOpus ? p.start : p.end) <= desired) chosen = i;
        }
        for (int i = preview.size() - 1; i >= chosen; i--) packets.ready.addFirst(preview.get(i));
        pending = nextPcm();
      } catch (IOException | RuntimeException failure) {
        releaseCodec();
        if (failure instanceof IOException io) throw io;
        throw new IOException("Ogg decoder resume failed", failure);
      }
    }

    byte[] nextPcm() throws IOException {
      Tagged t;
      while ((t = packets.next()) != null) {
        op.check();
        byte[] pcm =
            isOpus
                ? opus.decodePacket(ByteBuffer.wrap(t.data))
                : vorbis.decodePacket(ByteBuffer.wrap(t.data));
        int frames = pcm.length / (channels * 2);
        if (!isOpus && first) {
          first = false;
          preroll += frames;
          continue;
        }
        if (frames != t.samples)
          throw new IOException(
              "Ogg resumed packet duration mismatch " + frames + " vs " + t.samples);
        long low = Math.max(t.start, target + preSkip + granuleOrigin),
            high = Math.min(t.start + frames, totalGranule);
        if (high <= low) {
          preroll += frames;
          continue;
        }
        int begin = (int) (low - t.start), end = (int) (high - t.start);
        preroll += begin;
        op.progress();
        return Arrays.copyOfRange(pcm, begin * channels * 2, end * channels * 2);
      }
      return null;
    }

    public PcmFormat format() {
      return OggSeekDecoder.this.format();
    }

    /** Frames delivered since this result's sourceFrame; not a second copy of the source offset. */
    public long framePosition() {
      return delivered;
    }

    public int readFrames(byte[] out, int offset, int maxFrames) throws IOException {
      long bytes = (long) maxFrames * channels * 2;
      if (maxFrames < 0 || bytes > Integer.MAX_VALUE) throw new IndexOutOfBoundsException();
      Objects.checkFromIndexSize(offset, (int) bytes, out.length);
      if (maxFrames == 0) return 0;
      synchronized (publication) {
        ensureReader();
        if (reading) throw new IOException("Concurrent Ogg reader");
        reading = true;
      }
      try {
        if (pending == null) {
          if (delivered != totalFrames() - target)
            throw new EOFException("Ogg PCM ended before target span");
          return -1;
        }
        int count = 0;
        while (count < maxFrames && pending != null) {
          ensureReader();
          int n = Math.min(maxFrames - count, (pending.length - pendingOffset) / (channels * 2));
          System.arraycopy(
              pending, pendingOffset, out, offset + count * channels * 2, n * channels * 2);
          pendingOffset += n * channels * 2;
          count += n;
          if (pendingOffset == pending.length) {
            pending = nextPcm();
            pendingOffset = 0;
          }
        }
        synchronized (publication) {
          ensureReader();
          delivered += count;
          if (pending == null && delivered != totalFrames() - target)
            throw new EOFException("Ogg PCM duration mismatch");
          return count == 0 ? -1 : count;
        }
      } catch (IOException | RuntimeException failure) {
        try {
          close();
        } catch (IOException close) {
          failure.addSuppressed(close);
        }
        if (failure instanceof IOException io) throw io;
        throw new IOException("Ogg playback decode failed", failure);
      } finally {
        boolean release;
        synchronized (publication) {
          reading = false;
          release = readerClosed;
        }
        if (release) releaseCodec();
      }
    }

    private void ensureReader() throws IOException {
      if (readerClosed) throw new IOException("Ogg reader closed");
      op.check();
      if (Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Ogg reader interrupted");
    }

    private void releaseCodec() {
      synchronized (publication) {
        if (codecReleased) return;
        codecReleased = true;
      }
      if (opus != null) opus.close();
      if (vorbis != null) vorbis.close();
      if (info != null) info.clear();
    }

    public void close() throws IOException {
      boolean release;
      synchronized (publication) {
        if (readerClosed) return;
        readerClosed = true;
        release = !reading;
      }
      try {
        op.close();
      } finally {
        active.compareAndSet(op, null);
        if (release) releaseCodec();
      }
    }
  }

  public boolean isCurrent(long generation) {
    Operation op = active.get();
    return !closed && op != null && !op.cancelled && op.generation == generation;
  }

  public void cancel() throws IOException {
    Operation old = active.getAndSet(null);
    if (old != null) old.close();
  }

  public void close() throws IOException {
    if (closed) return;
    closed = true;
    cancel();
  }
}
