package indi.mopelotus.musichud.client.audio;

/** Shared volume policy for audible output and the playing cover indicator. */
public final class PlaybackGain {
  private PlaybackGain() {}

  public static float calculate(
      float volumePercent,
      boolean mixWithVanilla,
      boolean muted,
      float masterVolume,
      float musicVolume,
      float fadeGain) {
    if (muted) return 0;
    float gain = unit(volumePercent / 100) * unit(fadeGain);
    if (mixWithVanilla) gain *= unit(masterVolume) * unit(musicVolume);
    return gain;
  }

  private static float unit(float value) {
    return Float.isFinite(value) ? Math.clamp(value, 0, 1) : 0;
  }
}
