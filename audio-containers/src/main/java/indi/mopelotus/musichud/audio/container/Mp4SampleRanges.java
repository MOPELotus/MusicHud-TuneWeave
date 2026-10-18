package indi.mopelotus.musichud.audio.container;

import java.io.*;
import java.util.*;
import org.mp4parser.*;
import org.mp4parser.boxes.iso14496.part12.*;

/** Per-packet byte extents; never requests an entire MP4 chunk or fragment from an HTTP source. */
final class Mp4SampleRanges {
  final long[] offsets;
  final int[] sizes, descriptions;
  private final long fileSize;
  private int count;

  Mp4SampleRanges(IsoFile iso, TrackBox selected, int expected, long fileSize, long[] rootOffsets)
      throws IOException {
    this.fileSize = fileSize;
    offsets = new long[expected];
    sizes = new int[expected];
    descriptions = new int[expected];
    long trackId = selected.getTrackHeaderBox().getTrackId();
    var table = selected.getSampleTableBox();
    var stsz = table.getSampleSizeBox();
    long classic = stsz == null ? 0 : stsz.getSampleCount();
    if (classic > 0) {
      var stsc = table.getSampleToChunkBox();
      var stco = table.getChunkOffsetBox();
      if (stsc == null || stco == null) throw new IOException("Missing sample/chunk table");
      var map = stsc.getEntries();
      long[] chunks = stco.getChunkOffsets();
      if (map.isEmpty() || map.getFirst().getFirstChunk() != 1)
        throw new IOException("Invalid first chunk mapping");
      long previous = 0;
      for (var e : map) {
        if (e.getFirstChunk() <= previous
            || e.getFirstChunk() > chunks.length
            || e.getSamplesPerChunk() == 0) throw new IOException("Invalid chunk mapping");
        previous = e.getFirstChunk();
      }
      int entry = 0;
      for (int c = 0; c < chunks.length; c++) {
        while (entry + 1 < map.size() && map.get(entry + 1).getFirstChunk() <= c + 1) entry++;
        var mapping = map.get(entry);
        if (mapping.getSamplesPerChunk() > classic - count)
          throw new IOException("Chunk exceeds sample count");
        long offset = chunks[c];
        for (long j = 0; j < mapping.getSamplesPerChunk(); j++) {
          long bytes = stsz.getSampleSizeAtIndex(count);
          add(offset, bytes, mapping.getSampleDescriptionIndex());
          offset = Math.addExact(offset, bytes);
        }
      }
      if (count != classic) throw new IOException("Chunk/sample count mismatch");
    }
    Map<Long, TrackExtendsBox> defaults = new HashMap<>();
    for (var box : org.mp4parser.tools.Path.<TrackExtendsBox>getPaths(iso, "moov/mvex/trex"))
      defaults.put(box.getTrackId(), box);
    int rootIndex = 0;
    if (rootOffsets.length != iso.getBoxes().size()) throw new IOException("Root box map mismatch");
    for (Box box : iso.getBoxes()) {
      long rootOffset = rootOffsets[rootIndex++];
      if (box instanceof MovieFragmentBox moof) {
        long implicitBase = rootOffset;
        for (var traf : moof.getBoxes(TrackFragmentBox.class)) {
          var header = traf.getTrackFragmentHeaderBox();
          var trex = defaults.get(header.getTrackId());
          long base =
              header.hasBaseDataOffset()
                  ? header.getBaseDataOffset()
                  : header.isDefaultBaseIsMoof() ? rootOffset : implicitBase;
          long end = base;
          long defaultSize =
              header.hasDefaultSampleSize()
                  ? header.getDefaultSampleSize()
                  : trex == null ? 0 : trex.getDefaultSampleSize();
          long description =
              header.hasSampleDescriptionIndex()
                  ? header.getSampleDescriptionIndex()
                  : trex == null ? 1 : trex.getDefaultSampleDescriptionIndex();
          for (var run : traf.getBoxes(TrackRunBox.class)) {
            long at = run.isDataOffsetPresent() ? Math.addExact(base, run.getDataOffset()) : end;
            for (var e : run.getEntries()) {
              long bytes = run.isSampleSizePresent() ? e.getSampleSize() : defaultSize;
              if (header.getTrackId() == trackId) add(at, bytes, description);
              else check(at, bytes);
              at = Math.addExact(at, bytes);
            }
            end = at;
          }
          implicitBase = end;
        }
      }
    }
    if (count != expected) throw new IOException("Fragment/sample range count mismatch");
  }

  private void check(long offset, long bytes) throws IOException {
    if (offset < 0 || bytes < 0 || bytes > 16 * 1024 * 1024 || offset > fileSize - bytes)
      throw new IOException(
          "Sample range outside input or packet budget: "
              + offset
              + "+"
              + bytes
              + " > "
              + fileSize);
  }

  private void add(long offset, long bytes, long description) throws IOException {
    check(offset, bytes);
    if (count == offsets.length || description < 1 || description > Integer.MAX_VALUE)
      throw new IOException("Invalid sample index/description");
    offsets[count] = offset;
    sizes[count] = (int) bytes;
    descriptions[count] = (int) description;
    count++;
  }
}
