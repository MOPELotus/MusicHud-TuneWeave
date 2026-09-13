package indi.mopelotus.musichud.beans.api;

import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.network.ByteBufCodec;
import indi.mopelotus.musichud.network.Codecs;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.Objects;

@Getter
@ToString
public final class IdlePlaySource {
    public static final ByteBufCodec<IdlePlaySource> CODEC = ByteBufCodec.composite(
            Codecs.LONG,
            IdlePlaySource::getId,
            Codecs.CLASS,
            IdlePlaySource::getType,
            Codecs.ofEnum(IdlePlayMode.class), IdlePlaySource::getMode,
            IdlePlaySource::new
    );
    private final long id;
    private final Class<?> type;
    private IdlePlayMode mode = IdlePlayMode.RANDOM;
    private String sourceReference = "";
    transient private String lastReference;
    @Setter
    transient private PusherInfo pusherInfo;
    @Getter
    transient private boolean dataLoaded = false;
    transient private MusicCollection musicCollection;

    public IdlePlaySource(long id, Class<?> type) {
        this(id, type, IdlePlayMode.RANDOM);
    }

    public IdlePlaySource(long id, Class<?> type, IdlePlayMode mode) {
        if (type != Album.class && type != Playlist.class) throw new IllegalArgumentException("Unsupported idle source type");
        this.id = id;
        this.type = type;
        this.mode = Objects.requireNonNull(mode);
    }

    public IdlePlayMode getMode() { return mode == null ? IdlePlayMode.RANDOM : mode; }

    public String getSourceReference() { return sourceReference == null ? "" : sourceReference; }

    public IdlePlaySource withReference(String reference) {
        if (reference == null || reference.length() > 512 || (!reference.isBlank() && !reference.contains(":"))) {
            throw new IllegalArgumentException("Invalid idle source reference");
        }
        sourceReference = reference;
        return this;
    }

    public synchronized java.util.Optional<indi.mopelotus.musichud.beans.music.MusicDetail> nextTrack(java.util.random.RandomGenerator random) {
        if (musicCollection == null || musicCollection.getMusicDetails().isEmpty()) return java.util.Optional.empty();
        var tracks = java.util.List.copyOf(musicCollection.getMusicDetails());
        int index = 0;
        if (getMode() == IdlePlayMode.RANDOM) index = random.nextInt(tracks.size());
        else if (lastReference != null) {
            for (int i = 0; i < tracks.size(); i++) {
                if (lastReference.equals(tracks.get(i).getSourceRef())) { index = (i + 1) % tracks.size(); break; }
            }
        }
        var track = tracks.get(index);
        lastReference = track.getSourceRef();
        return java.util.Optional.of(track);
    }

    public void useClientCollection(MusicCollection collection) {
        if (collection == null || collection.getId() != id || collection.getClass() != type) {
            throw new IllegalArgumentException("Idle source snapshot identity mismatch");
        }
        this.musicCollection = collection;
        this.dataLoaded = true;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        IdlePlaySource that = (IdlePlaySource) o;
        return id == that.id && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, type);
    }
}
