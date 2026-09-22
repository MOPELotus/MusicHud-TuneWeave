package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.api.IdlePlayMode;
import indi.mopelotus.musichud.beans.api.IdlePlaySource;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import java.util.*;
import java.util.random.RandomGenerator;

/** Upstream sequential position survives source replacement, mode changes and owner reconnects. */
public final class IdleSequentialProgress {
    private record Key(UUID owner, Class<?> type, long id) {}
    private final Map<Key, String> lastTracks = new HashMap<>();

    public synchronized Optional<MusicDetail> select(IdlePlaySource source, UUID owner,
                                                    RandomGenerator random, String excluded) {
        if (source.getMode() != IdlePlayMode.SEQUENTIAL) return source.nextTrackExcept(random, excluded);
        Key key = new Key(owner, source.getType(), source.getId());
        source.resumeAfter(lastTracks.get(key));
        Optional<MusicDetail> selected = source.nextTrackExcept(random, excluded);
        selected.ifPresent(track -> lastTracks.put(key, track.getSourceRef()));
        return selected;
    }

    public synchronized void reset() { lastTracks.clear(); }
}
