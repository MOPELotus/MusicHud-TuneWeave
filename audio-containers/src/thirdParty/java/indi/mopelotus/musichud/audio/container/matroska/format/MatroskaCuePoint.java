package indi.mopelotus.musichud.audio.container.matroska.format;

/**
 * Matroska file cue point. Provides the offsets at a specific timecode for each track
 */
public class MatroskaCuePoint {
    /**
     * Timecode using the file timescale
     */
    public final long timecode;
    /**
     * Absolute offset to the cluster
     */
    public final java.util.Map<Integer,Long> trackClusterOffsets;

    /**
     * @param timecode            Timecode using the file timescale
     * @param trackClusterOffsets Absolute offset to the cluster
     */
    public MatroskaCuePoint(long timecode, java.util.Map<Integer,Long> trackClusterOffsets) {
        this.timecode = timecode;
        this.trackClusterOffsets = java.util.Map.copyOf(trackClusterOffsets);
    }
}
