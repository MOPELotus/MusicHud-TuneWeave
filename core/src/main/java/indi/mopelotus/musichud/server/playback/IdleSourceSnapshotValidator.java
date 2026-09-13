package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import java.util.Set;

/** Copies client metadata into server-owned source state, never resolving provider APIs. */
public final class IdleSourceSnapshotValidator {
    private IdleSourceSnapshotValidator() {}

    public static MusicCollection copy(MusicCollection input, PusherInfo owner) {
        if (!(input instanceof Playlist) && !(input instanceof Album)) throw new IllegalArgumentException("Unsupported source");
        if (input.getName().isBlank() || input.getName().length() > 500
                || input.getMusicDetails().size() > 10_000) throw new IllegalArgumentException("Invalid source metadata");
        String reference = input instanceof Playlist p ? p.getSourceRef() : ((Album) input).getSourceRef();
        requireReference(reference);
        var tracks = new ObservableSequencedSet<MusicDetail>();
        for (MusicDetail track : input.getMusicDetails()) {
            if (track == null) throw new IllegalArgumentException("Missing source track");
            requireReference(track.getSourceRef());
            if (!Set.of("track", "video", "podcast_episode", "radio_station").contains(track.getSourceKind())
                    || track.getName().isBlank() || track.getName().length() > 500
                    || track.getDurationMillis() <= 0 || track.getDurationMillis() > 86_400_000
                    || track.getSourcePartRef().length() > 512) throw new IllegalArgumentException("Invalid source track");
            MusicDetail copy = MusicDetail.fromTuneWeave(track.getId(), track.getSourceRef(), track.getSourceKind(),
                    track.getName(), track.getDurationMillis(), track.getAlbum(), java.util.List.copyOf(track.getArtists()));
            copy.setSourcePartRef(track.getSourcePartRef());
            copy.setCloudSource(track.isCloudSource());
            copy.setClientHostedUni(track.isClientHostedUni());
            copy.setPusherInfo(owner);
            tracks.add(copy);
        }
        MusicCollection copy = input.copyWithPusherInfo(owner);
        if (copy instanceof Playlist playlist) { playlist.setTracks(tracks); playlist.setMusicTrackCount(tracks.size()); }
        else ((Album) copy).setMusicDetails(tracks);
        return copy;
    }

    private static void requireReference(String ref) {
        if (ref == null || ref.isBlank() || ref.length() > 512 || !ref.contains(":")) {
            throw new IllegalArgumentException("Invalid source reference");
        }
    }
}
