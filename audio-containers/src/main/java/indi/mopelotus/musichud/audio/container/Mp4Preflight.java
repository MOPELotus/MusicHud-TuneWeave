package indi.mopelotus.musichud.audio.container;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

/** Validate allocation-driving tables before MP4parser can lazily allocate their arrays. */
final class Mp4Preflight {
  private final SeekableByteChannel input;
  private final long fileSize;
  private final long budget;
  private long metadataBytes, indexBytes;
  private final java.util.List<Long> rootOffsets = new java.util.ArrayList<>();
  private int boxes;
  private final ByteBuffer window = ByteBuffer.allocate(65536);
  private long windowStart = -1;
  private static final Set<String> CONTAINERS =
      Set.of("moov", "trak", "mdia", "minf", "stbl", "edts", "mvex", "moof", "traf");
  private static final Set<String> PARSED =
      Set.of(
          "mvhd", "tkhd", "mdhd", "hdlr", "stsd", "stsz", "stts", "ctts", "stsc", "stco", "co64",
          "elst", "trex", "tfhd", "tfdt", "trun", "mp4a", "alac", ".mp3", "esds");

  private Mp4Preflight(SeekableByteChannel input) throws IOException {
    this.input = input;
    fileSize = input.size();
    budget =
        Math.max(256 * 1024, Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8));
  }

  static long[] validate(SeekableByteChannel input) throws IOException {
    long old = input.position();
    try {
      var scanner = new Mp4Preflight(input);
      scanner.scan(0, input.size(), 0, "");
      return scanner.rootOffsets.stream().mapToLong(Long::longValue).toArray();
    } catch (ArithmeticException | IllegalArgumentException e) {
      throw new IOException("MP4 table size overflow", e);
    } finally {
      input.position(old);
    }
  }

  private void chargeIndex(long count, long width) throws IOException {
    indexBytes = Math.addExact(indexBytes, Math.multiplyExact(count, width));
    if (indexBytes > budget) throw new IOException("MP4 index exceeds memory budget");
  }

  private ByteBuffer read(long at, int length) throws IOException {
    if (at < 0 || length < 0 || at > fileSize - length)
      throw new EOFException("MP4 field outside file");
    if (windowStart < 0 || at < windowStart || at + length > windowStart + window.limit()) {
      window.clear();
      window.limit((int) Math.min(window.capacity(), fileSize - at));
      input.position(at);
      int stalled = 0;
      while (window.hasRemaining()) {
        int n = input.read(window);
        if (n < 0) throw new EOFException("Truncated MP4");
        if (n == 0) {
          if (++stalled > 8) throw new IOException("MP4 input made no progress");
        } else stalled = 0;
      }
      window.flip();
      windowStart = at;
    }
    var view = window.duplicate();
    view.position((int) (at - windowStart));
    view.limit((int) (at - windowStart) + length);
    return view.slice();
  }

  private long u32(long at) throws IOException {
    return Integer.toUnsignedLong(read(at, 4).getInt());
  }

  private void table(
      long payload, long size, int prefix, int entrySize, long count, long countOffset)
      throws IOException {
    if (count > Integer.MAX_VALUE || size < prefix || count > (size - prefix) / entrySize)
      throw new IOException("MP4 table count exceeds payload at " + countOffset);
    chargeIndex(count, Math.max(32, entrySize * 2L));
  }

  private void scan(long start, long end, int depth, String parent) throws IOException {
    if (depth > 24) throw new IOException("MP4 nesting exceeds bound");
    long p = start;
    while (p < end) {
      if (depth == 0) rootOffsets.add(p);
      if (++boxes > 100000) throw new IOException("Too many MP4 boxes");
      chargeIndex(1, 128);
      if (end - p < 8) throw new EOFException("Truncated MP4 box header");
      ByteBuffer h = read(p, 8);
      long size = Integer.toUnsignedLong(h.getInt());
      byte[] typeBytes = new byte[4];
      h.get(typeBytes);
      String type = new String(typeBytes, java.nio.charset.StandardCharsets.ISO_8859_1);
      int header = 8;
      if (size == 1) {
        if (end - p < 16) throw new EOFException("Truncated MP4 extended header");
        size = read(p + 8, 8).getLong();
        header = 16;
      } else if (size == 0) size = end - p;
      if (size < header || size > end - p) throw new EOFException("MP4 box extent outside parent");
      long payload = p + header, n = size - header;
      if (PARSED.contains(type)) {
        if (!CONTAINERS.contains(type)) {
          long allocated =
              type.equals("stsd") ? Math.min(n, 8) : parent.equals("stsd") ? Math.min(n, 64) : n;
          if ((type.equals("esds") || type.equals("alac") && !parent.equals("stsd"))
              && allocated > 1024 * 1024)
            throw new IOException("Codec configuration exceeds budget");
          metadataBytes = Math.addExact(metadataBytes, allocated);
          if (allocated > budget || metadataBytes > budget)
            throw new IOException("MP4 metadata exceeds memory budget");
        }
      }
      if (CONTAINERS.contains(type)) scan(payload, p + size, depth + 1, type);
      else if (type.equals("stsz")) {
        if (n < 12) throw new EOFException("Truncated stsz");
        long fixed = u32(payload + 4), count = u32(payload + 8);
        if (fixed == 0) table(payload, n, 12, 4, count, payload + 8);
        chargeIndex(count, 32);
        long total = 0;
        for (long j = 0; j < count; j++) {
          long bytes = fixed == 0 ? u32(payload + 12 + j * 4) : fixed;
          if (bytes > Math.min(8L * 1024 * 1024, budget))
            throw new IOException("MP4 packet exceeds bound");
          total = Math.addExact(total, bytes);
          if (total > fileSize) throw new IOException("MP4 sample bytes exceed file extent");
        }
      } else if (Set.of("stts", "ctts", "stsc", "stco", "co64", "stss", "stsh", "sbgp")
          .contains(type)) {
        if (n < 8) throw new EOFException("Truncated " + type);
        int prefix = 8,
            width =
                switch (type) {
                  case "stsc" -> 12;
                  case "stco", "stss" -> 4;
                  default -> 8;
                };
        long count;
        if (type.equals("sbgp")) {
          if (n < 12) throw new EOFException("Truncated sbgp");
          int version = read(payload, 1).get() & 255;
          prefix = version == 1 ? 16 : 12;
          if (n < prefix) throw new EOFException("Truncated sbgp");
          count = u32(payload + prefix - 4);
        } else count = u32(payload + 4);
        table(payload, n, prefix, width, count, payload + prefix - 4);
        if (type.equals("stco") || type.equals("co64")) {
          for (long j = 0; j < count; j++) {
            long offset =
                type.equals("stco")
                    ? u32(payload + prefix + j * width)
                    : read(payload + prefix + j * width, 8).getLong();
            if (offset < 0 || offset > fileSize)
              throw new IOException("MP4 chunk offset outside file");
          }
        }
        if (type.equals("stts") || type.equals("ctts")) {
          long expanded = 0;
          for (long j = 0; j < count; j++)
            expanded = Math.addExact(expanded, u32(payload + prefix + j * 8));
          chargeIndex(expanded, type.equals("stts") ? 24 : 8);
        }
      } else if (type.equals("stz2")) {
        if (n < 12) throw new EOFException("Truncated stz2");
        int bits = read(payload + 7, 1).get() & 255;
        if (bits != 4 && bits != 8 && bits != 16) throw new IOException("Invalid stz2 field size");
        long count = u32(payload + 8);
        if (count > (n - 12) * 8 / bits) throw new IOException("stz2 exceeds payload");
        chargeIndex(count, 32);
      } else if (type.equals("trun")) {
        if (n < 8) throw new EOFException("Truncated trun");
        int flags = read(payload, 4).getInt() & 0xffffff;
        long count = u32(payload + 4);
        int prefix = 8 + ((flags & 1) != 0 ? 4 : 0) + ((flags & 4) != 0 ? 4 : 0);
        int width = 4 * Integer.bitCount(flags & 0xf00);
        if (n < prefix || width != 0 && count > (n - prefix) / width)
          throw new IOException("trun exceeds payload");
        chargeIndex(count, 64);
        if ((flags & 0x200) != 0) {
          int sizeOffset = ((flags & 0x100) != 0 ? 4 : 0);
          long sum = 0;
          for (long j = 0; j < count; j++) {
            long bytes = u32(payload + prefix + j * width + sizeOffset);
            if (bytes > Math.min(8L * 1024 * 1024, budget))
              throw new IOException("Fragment packet exceeds bound");
            sum = Math.addExact(sum, bytes);
            if (sum > fileSize) throw new IOException("Fragment sample bytes exceed file");
          }
        }
      } else if (type.equals("stsd")) {
        if (n < 8) throw new EOFException("Truncated stsd");
        long entries = u32(payload + 4);
        if (entries > (n - 8) / 8) throw new IOException("stsd count exceeds payload");
        chargeIndex(entries, 256);
        scan(payload + 8, p + size, depth + 1, type);
      } else if (parent.equals("stsd") && Set.of("mp4a", "alac", ".mp3").contains(type)) {
        if (n < 28) throw new EOFException("Truncated audio sample entry");
        int version = Short.toUnsignedInt(read(payload + 8, 2).getShort());
        int extra = version == 1 ? 16 : version == 2 ? 36 : 0;
        if (n < 28 + extra) throw new EOFException("Truncated extended audio sample entry");
        scan(payload + 28 + extra, p + size, depth + 1, type);
      }
      p = Math.addExact(p, size);
    }
  }
}
