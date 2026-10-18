package indi.mopelotus.musichud.audio.container;

import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/** JDK-only child process: no unshaded container or codec can satisfy these checks. */
public final class ShadedFragmentMetadataProbe {
  public static void main(String[] args) throws Exception {
    try (var loader =
        new URLClassLoader(
            new java.net.URL[] {
              Path.of(args[0]).toUri().toURL(),
              Path.of(args[1]).toUri().toURL(),
              Path.of(args[2]).toUri().toURL()
            },
            ClassLoader.getPlatformClassLoader())) {
      loader.setDefaultAssertionStatus(Boolean.parseBoolean(args[5]));
      var sourceType = loader.loadClass("indi.mopelotus.musichud.audio.container.Mp4PacketSource");
      try (var source =
          (AutoCloseable) sourceType.getConstructor(Path.class).newInstance(Path.of(args[3]))) {
        if (!Boolean.TRUE.equals(sourceType.getMethod("hasOpenEndedFinalEdit").invoke(source)))
          throw new AssertionError("Lost open-ended edit marker in production jar");
        Object track = sourceType.getMethod("track").invoke(source);
        var edits = (java.util.List<?>) track.getClass().getMethod("edits").invoke(track);
        Object edit = edits.getLast();
        Object mediaStart = edit.getClass().getMethod("mediaStart").invoke(edit);
        long start =
            ((Number) mediaStart.getClass().getMethod("ticks").invoke(mediaStart)).longValue();
        if (start != Long.parseLong(args[6])) throw new AssertionError("Lost raw edit priming");
        Object time = track.getClass().getMethod("mediaDuration").invoke(track);
        long ticks = (long) time.getClass().getMethod("ticks").invoke(time);
        long scale = ((Number) time.getClass().getMethod("timescale").invoke(time)).longValue();
        if (ticks != 63424 || scale != 48000)
          throw new AssertionError("Incorrect fragment endpoint");
      }
    }
    ShadedPacketPcmProbe.main(Arrays.copyOf(args, 6));
  }
}
