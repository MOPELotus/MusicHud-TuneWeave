package indi.mopelotus.musichud.audio.container;

import java.io.*;
import java.util.*;
import org.mp4parser.*;
import org.mp4parser.boxes.iso14496.part12.*;

/** Expands only the public audio timing tables; no muxer, sample cache, groups, or writer graph. */
final class Mp4SampleTiming {
  static long[] durations(IsoFile iso, TrackBox selected) throws IOException {
    long trackId = selected.getTrackHeaderBox().getTrackId();
    var table = selected.getSampleTableBox();
    var stsz = table.getSampleSizeBox();
    long initial = stsz == null ? 0 : stsz.getSampleCount(), total = initial;
    for (var moof : iso.getBoxes(MovieFragmentBox.class))
      for (var traf : moof.getBoxes(TrackFragmentBox.class))
        if (traf.getTrackFragmentHeaderBox().getTrackId() == trackId)
          for (var run : traf.getBoxes(TrackRunBox.class))
            total = Math.addExact(total, run.getSampleCount());
    long budget =
        Math.max(256 * 1024, Math.min(64L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8));
    if (total > Integer.MAX_VALUE || total > budget / 32)
      throw new IOException("Timing index exceeds memory budget");
    long[] result = new long[(int) total];
    int at = 0;
    var stts = table.getTimeToSampleBox();
    if (stts != null)
      for (var entry : stts.getEntries()) {
        if (entry.getCount() < 0 || entry.getCount() > initial - at || entry.getDelta() < 0)
          throw new IOException("Invalid stts sample count/duration");
        Arrays.fill(result, at, at + (int) entry.getCount(), entry.getDelta());
        at += entry.getCount();
      }
    if (at != initial) throw new IOException("stts/stsz sample count mismatch");
    TrackExtendsBox defaults = null;
    for (var trex : org.mp4parser.tools.Path.<TrackExtendsBox>getPaths(iso, "moov/mvex/trex"))
      if (trex.getTrackId() == trackId) {
        if (defaults != null) throw new IOException("Duplicate track defaults");
        defaults = trex;
      }
    for (var moof : iso.getBoxes(MovieFragmentBox.class))
      for (var traf : moof.getBoxes(TrackFragmentBox.class))
        if (traf.getTrackFragmentHeaderBox().getTrackId() == trackId) {
          var header = traf.getTrackFragmentHeaderBox();
          long fallback =
              header.hasDefaultSampleDuration()
                  ? header.getDefaultSampleDuration()
                  : defaults == null ? 0 : defaults.getDefaultSampleDuration();
          for (var run : traf.getBoxes(TrackRunBox.class)) {
            if (run.getEntries().size() != run.getSampleCount())
              throw new IOException("Fragment entry count mismatch");
            for (var entry : run.getEntries()) {
              long duration = run.isSampleDurationPresent() ? entry.getSampleDuration() : fallback;
              if (duration < 0 || at == result.length)
                throw new IOException("Invalid fragment duration/count");
              result[at++] = duration;
            }
          }
        }
    if (at != result.length) throw new IOException("Timing count mismatch");
    return result;
  }
}
