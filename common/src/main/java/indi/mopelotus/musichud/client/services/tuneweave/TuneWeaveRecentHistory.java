package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Client-owned account records. These timestamps and devices never enter Minecraft packets. */
public record TuneWeaveRecentHistory(Kind kind, List<Entry<?>> entries, Long total) {
    public static final int LIMIT = 100;

    public TuneWeaveRecentHistory {
        Objects.requireNonNull(kind);
        entries = List.copyOf(entries);
        if (entries.size() > LIMIT || total != null && total < 0
                || entries.stream().anyMatch(entry -> !kind.type.isInstance(entry.resource()))) {
            throw new IllegalArgumentException("Invalid recent history page");
        }
    }

    public enum Kind {
        TRACKS("tracks", "track", MusicDetail.class),
        ALBUMS("albums", "album", Album.class),
        PLAYLISTS("playlists", "playlist", Playlist.class);

        private final String path, field;
        private final Class<?> type;
        Kind(String path, String field, Class<?> type) {
            this.path = path; this.field = field; this.type = type;
        }
        public String path() { return path; }
        String field() { return field; }
        String capability() { return "recent_" + field + "_history"; }
    }

    public record Entry<T>(T resource, Instant playedAt, Device device) {
        public Entry { Objects.requireNonNull(resource); }
    }

    public record Device(String name, String operatingSystem) {
        public Device {
            name = Objects.requireNonNullElse(name, "");
            operatingSystem = Objects.requireNonNullElse(operatingSystem, "");
        }
        public String displayName() { return name.isBlank() ? operatingSystem : name; }
    }
}
