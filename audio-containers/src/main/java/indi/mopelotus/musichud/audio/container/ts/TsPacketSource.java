package indi.mopelotus.musichud.audio.container.ts;

import java.io.*;
import java.util.*;

/** Bounded AAC TS demuxer with owned input; MPEG timestamps remain in 90 kHz ticks. */
public final class TsPacketSource implements AutoCloseable {
  public record Packet(byte[] data, long pts90k, boolean discontinuity, int streamType) {}

  private final InputStream input;
  private final Map<Integer, Integer> counters = new HashMap<>();
  private final Map<Integer, byte[]> lastPackets = new HashMap<>();
  private final Map<Integer, ByteArrayOutputStream> sections = new HashMap<>();
  private int pmt = -1, pid = -1, type = -1, expected = -1;
  private ByteArrayOutputStream pes;
  private boolean discontinuity = true, eof;
  private volatile boolean closed;
  private long previousPts = Long.MIN_VALUE, wrapBase;

  public TsPacketSource(InputStream input) {
    this.input = Objects.requireNonNull(input);
  }

  public Packet next() throws IOException {
    checkOpen();
    while (!eof) {
      byte[] t = input.readNBytes(188);
      checkOpen();
      if (t.length == 0) {
        eof = true;
        if (pes != null) throw new EOFException("Truncated PES");
        for (var section : sections.values())
          if (section.size() > 0) throw new EOFException("Truncated PSI");
        return null;
      }
      if (t.length != 188) throw new EOFException("Truncated TS packet");
      if ((t[0] & 255) != 0x47) throw new IOException("TS sync lost");
      int id = ((t[1] & 31) << 8) | (t[2] & 255);
      boolean start = (t[1] & 64) != 0;
      if ((t[1] & 128) != 0) throw new IOException("TS transport error");
      if ((t[3] & 192) != 0) throw new IOException("Encrypted TS");
      int af = (t[3] >>> 4) & 3, cc = t[3] & 15, pos = 4;
      boolean reset = false;
      if (af == 0) throw new IOException("Reserved TS adaptation control");
      if ((af & 2) != 0) {
        int n = t[pos++] & 255;
        if (n > 188 - pos) throw new IOException("TS adaptation length");
        reset = n > 0 && (t[pos] & 128) != 0;
        pos += n;
      }
      if (id != 0 && id != pmt && id != pid) continue;
      if (reset) {
        counters.remove(id);
        lastPackets.remove(id);
        sections.remove(id);
        if (id == pid) {
          pes = null;
          expected = -1;
          discontinuity = true;
          previousPts = Long.MIN_VALUE;
          wrapBase = 0;
        }
      }
      if ((af & 1) == 0) continue;
      if (pos == 188) throw new IOException("Empty TS payload");
      Integer prev = counters.get(id);
      if (prev != null) {
        if (cc == prev) {
          if (Arrays.equals(lastPackets.get(id), t)) continue;
          throw new IOException("Conflicting TS duplicate");
        }
        if (cc != ((prev + 1) & 15)) throw new IOException("TS continuity gap");
      }
      counters.put(id, cc);
      lastPackets.put(id, t.clone());
      if (id == 0 || id == pmt) {
        psi(id, t, pos, start);
        continue;
      }
      if (start) {
        if (pes != null) throw new IOException("PES ended before declared length");
        pes = new ByteArrayOutputStream();
        expected = -1;
      }
      if (pes == null) continue;
      int n = 188 - pos;
      if (expected < 0) {
        int need = Math.min(n, 6 - pes.size());
        pes.write(t, pos, need);
        pos += need;
        n -= need;
        if (pes.size() == 6) {
          byte[] h = pes.toByteArray();
          if (h[0] != 0 || h[1] != 0 || h[2] != 1) throw new IOException("PES sync");
          if ((h[3] & 0xe0) != 0xc0 && (h[3] & 255) != 0xbd) throw new IOException("Not audio PES");
          int len = ((h[4] & 255) << 8) | (h[5] & 255);
          if (len < 3) throw new IOException("Unbounded/short audio PES unsupported");
          expected = 6 + len;
        }
      }
      if (expected >= 0) {
        int take = Math.min(n, expected - pes.size());
        pes.write(t, pos, take);
        if (pes.size() == expected) {
          byte[] b = pes.toByteArray();
          pes = null;
          expected = -1;
          return decode(b);
        }
      }
    }
    return null;
  }

  private Packet decode(byte[] b) throws IOException {
    if ((b[6] & 192) != 128) throw new IOException("PES marker");
    int flags = (b[7] >>> 6) & 3, h = b[8] & 255;
    if (h > b.length - 9) throw new IOException("PES header exceeds packet");
    if (flags == 1) throw new IOException("Reserved PTS flags");
    long pts = Long.MIN_VALUE;
    if (flags >= 2) {
      if (h < (flags == 3 ? 10 : 5)) throw new IOException("Truncated PTS/DTS");
      pts = timestamp(b, 9, flags == 3 ? 3 : 2);
      if (flags == 3) timestamp(b, 14, 1);
      if (previousPts != Long.MIN_VALUE) {
        long delta = pts - previousPts;
        if (delta < -(1L << 32)) wrapBase += 1L << 33;
        else if (delta > (1L << 32)) wrapBase -= 1L << 33;
      }
      previousPts = pts;
      pts += wrapBase;
    }
    boolean d = discontinuity;
    discontinuity = false;
    return new Packet(Arrays.copyOfRange(b, 9 + h, b.length), pts, d, type);
  }

  private static long timestamp(byte[] b, int p, int prefix) throws IOException {
    if ((b[p] >>> 4 & 15) != prefix
        || (b[p] & 1) == 0
        || (b[p + 2] & 1) == 0
        || (b[p + 4] & 1) == 0) throw new IOException("Invalid timestamp marker");
    return ((long) (b[p] & 14) << 29)
        | ((long) (b[p + 1] & 255) << 22)
        | ((long) (b[p + 2] & 254) << 14)
        | ((long) (b[p + 3] & 255) << 7)
        | ((b[p + 4] & 254) >>> 1);
  }

  private void psi(int id, byte[] t, int pos, boolean start) throws IOException {
    var pending = sections.get(id);
    if (start) {
      int pointer = t[pos++] & 255;
      if (pointer > 188 - pos) throw new IOException("PSI pointer");
      if (pending != null && pending.size() > 0) {
        pending.write(t, pos, pointer);
        consume(id, pending, true);
      }
      pos += pointer;
      pending = new ByteArrayOutputStream();
      sections.put(id, pending);
    } else if (pending == null) return;
    pending.write(t, pos, 188 - pos);
    consume(id, pending, false);
  }

  private void consume(int id, ByteArrayOutputStream pending, boolean requireEnd)
      throws IOException {
    byte[] b = pending.toByteArray();
    int p = 0;
    while (p < b.length) {
      if ((b[p] & 255) == 255) {
        p = b.length;
        break;
      }
      if (b.length - p < 3) break;
      int len = ((b[p + 1] & 15) << 8) | (b[p + 2] & 255);
      if (len > 1021 || len < 9) throw new IOException("PSI section length");
      if (b.length - p < 3 + len) break;
      byte[] section = Arrays.copyOfRange(b, p, p + 3 + len);
      if (crc(section) != 0) throw new IOException("PSI CRC");
      table(id, section);
      p += 3 + len;
    }
    pending.reset();
    pending.write(b, p, b.length - p);
    if (requireEnd && pending.size() != 0) throw new IOException("Truncated PSI before pointer");
  }

  private void table(int id, byte[] b) throws IOException {
    if ((b[1] & 128) == 0) throw new IOException("PSI syntax");
    if ((b[5] & 1) == 0) return;
    if (b[6] != 0 || b[7] != 0) throw new IOException("Multi-section program tables unsupported");
    int end = b.length - 4;
    if (id == 0) {
      if (b[0] != 0) throw new IOException("PAT table id");
      if ((end - 8) % 4 != 0) throw new IOException("PAT entries");
      int next = -1;
      for (int p = 8; p < end; p += 4) {
        int program = ((b[p] & 255) << 8) | (b[p + 1] & 255);
        if (program != 0 && next < 0) next = ((b[p + 2] & 31) << 8) | (b[p + 3] & 255);
      }
      if (next != pmt) {
        sections.remove(pmt);
        pmt = next;
        pid = -1;
        pes = null;
        expected = -1;
        discontinuity = true;
      }
    } else {
      if (b[0] != 2 || end < 12) throw new IOException("PMT table id/size");
      int p = 12 + (((b[10] & 15) << 8) | (b[11] & 255));
      if (p > end) throw new IOException("PMT descriptors");
      int next = -1, nt = -1;
      while (p < end) {
        if (end - p < 5) throw new IOException("PMT entry truncated");
        int st = b[p] & 255,
            sp = ((b[p + 1] & 31) << 8) | (b[p + 2] & 255),
            n = ((b[p + 3] & 15) << 8) | (b[p + 4] & 255);
        p += 5;
        if (n > end - p) throw new IOException("PMT ES descriptors");
        if ((st == 15 || st == 17) && next < 0) {
          next = sp;
          nt = st;
        }
        p += n;
      }
      if (next != pid || nt != type) {
        counters.remove(next);
        lastPackets.remove(next);
        pid = next;
        type = nt;
        pes = null;
        expected = -1;
        discontinuity = true;
        previousPts = Long.MIN_VALUE;
        wrapBase = 0;
      }
    }
  }

  static int crc(byte[] b) {
    int crc = -1;
    for (byte v : b) {
      crc ^= (v & 255) << 24;
      for (int j = 0; j < 8; j++) crc = (crc << 1) ^ ((crc < 0) ? 0x04c11db7 : 0);
    }
    return crc;
  }

  private void checkOpen() throws IOException {
    if (closed) throw new IOException("TS source closed");
  }

  /** Concurrent close releases a blocked input without holding the reader monitor. */
  public synchronized void close() throws IOException {
    if (closed) return;
    closed = true;
    input.close();
  }
}
