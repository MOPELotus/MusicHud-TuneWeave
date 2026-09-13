package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.beans.user.VipType;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.EnumMap;
import java.util.concurrent.CancellationException;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.*;

/** Converts TuneWeave snapshots to normalized Minecraft-domain entities and owns their identity cache. */
final class TuneWeaveEntityMapper {
    private final Map<Long, MusicDetail> tracks = new ConcurrentHashMap<>();
    private final Map<ScopedTrackKey, MusicDetail> scopedTracks = new ConcurrentHashMap<>();
    private final Map<Long, Playlist> playlists = new ConcurrentHashMap<>();
    private final Map<ScopedPlaylistKey, Playlist> scopedPlaylists = new ConcurrentHashMap<>();
    private final Map<Long, Album> albums = new ConcurrentHashMap<>();
    private final Map<ScopedReferenceKey, Album> scopedAlbums = new ConcurrentHashMap<>();
    private final Map<Long, Artist> artists = new ConcurrentHashMap<>();
    private final Map<ScopedReferenceKey, Artist> scopedArtists = new ConcurrentHashMap<>();
    private final Function<TuneWeavePlatform, TuneWeaveSession> sessionLookup;
    private final ThreadLocal<MappingContext> boundContext = new ThreadLocal<>();
    private Object generation = new Object();

    private record MappingContext(Object generation, Map<TuneWeavePlatform, TuneWeaveSession> sessions) {}

    synchronized Object captureEpoch() { return generation; }
    synchronized void requireEpoch(Object expected) {
        if (generation != expected) throw new CancellationException("Entity mapping scope changed");
    }

    synchronized <T> Supplier<T> capture(Supplier<T> operation) {
        MappingContext active = boundContext.get();
        if (active == null) {
            Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
            for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
                TuneWeaveSession session = sessionLookup.apply(platform);
                if (session != null) sessions.put(platform, session);
            }
            active = new MappingContext(generation, Map.copyOf(sessions));
        }
        MappingContext context = active;
        return () -> {
            MappingContext previous = boundContext.get();
            boundContext.set(context);
            try {
                synchronized (this) { requireCurrentContext(); }
                T result = operation.get();
                synchronized (this) { requireCurrentContext(); }
                return result;
            } finally {
                if (previous == null) boundContext.remove(); else boundContext.set(previous);
            }
        };
    }

    synchronized void clear() {
        generation = new Object();
        tracks.clear(); scopedTracks.clear();
        playlists.clear(); scopedPlaylists.clear();
        albums.clear(); scopedAlbums.clear();
        artists.clear(); scopedArtists.clear();
    }

    private void requireCurrentContext() {
        MappingContext context = boundContext.get();
        if (context == null) return;
        if (context.generation() != generation) throw new CancellationException("Entity mapping scope changed");
        for (TuneWeavePlatform platform : TuneWeavePlatform.values()) {
            if (!AccountScope.fromSession(context.sessions().get(platform))
                    .equals(AccountScope.fromSession(sessionLookup.apply(platform)))) {
                throw new CancellationException("Entity mapping account changed");
            }
        }
    }

    private TuneWeaveSession session(TuneWeavePlatform platform) {
        MappingContext context = boundContext.get();
        return context == null ? sessionLookup.apply(platform) : context.sessions().get(platform);
    }

    AccountScope accountScope(TuneWeavePlatform platform) {
        return AccountScope.fromSession(session(platform));
    }

    TuneWeaveEntityMapper(Function<TuneWeavePlatform, TuneWeaveSession> sessionLookup) {
        this.sessionLookup = Objects.requireNonNull(sessionLookup);
    }

    boolean hasPlaylist(long id) {
        return playlist(id) != null;
    }

    boolean hasAlbum(long id) {
        return album(id) != null;
    }

    boolean hasArtist(long id) {
        return artist(id) != null;
    }

    Playlist playlist(long id) {
        Playlist value = playlists.get(id);
        return value == null ? null : playlist(scopeForReference(value.getSourceRef()), value.getSourceRef());
    }

    Collection<Playlist> playlists() {
        return playlists.values();
    }

    public Playlist playlist(AccountScope scope, String reference) {
        return scopedPlaylists.get(new ScopedPlaylistKey(scope, reference));
    }

    Album album(long id) {
        Album value = albums.get(id);
        return value == null ? null : album(scopeForReference(value.getSourceRef()), value.getSourceRef());
    }

    Artist artist(long id) {
        Artist value = artists.get(id);
        return value == null ? null : artist(scopeForReference(value.getSourceRef()), value.getSourceRef());
    }

    private AccountScope scopeForReference(String reference) {
        return accountScope(TuneWeaveReference.platformOrDefault(reference, TuneWeavePlatform.NETEASE));
    }

    synchronized void cacheTrack(MusicDetail track) {
        requireCurrentContext();
        if (track != null && track != MusicDetail.NONE) {
            tracks.put(track.getId(), track);
            TuneWeavePlatform platform = TuneWeaveReference.platformOrDefault(track.getSourceRef(), TuneWeavePlatform.NETEASE);
            if (track.getDurationMillis() > 0) {
                scopedTracks.put(new ScopedTrackKey(AccountScope.fromSession(session(platform)), track.getSourceRef()), track);
            }
        }
    }

    Album album(AccountScope scope, String reference) { return scopedAlbums.get(new ScopedReferenceKey(scope, reference)); }
    Artist artist(AccountScope scope, String reference) { return scopedArtists.get(new ScopedReferenceKey(scope, reference)); }

    MusicDetail track(AccountScope scope, String reference) {
        return scopedTracks.get(new ScopedTrackKey(scope, reference));
    }

    synchronized void cachePlaylist(Playlist playlist) {
        requireCurrentContext();
        if (playlist != null && playlist != Playlist.EMPTY) {
            playlists.put(playlist.getId(), playlist);
            TuneWeavePlatform platform = TuneWeaveReference.platformOrDefault(
                    playlist.getSourceRef(), TuneWeavePlatform.NETEASE);
            TuneWeaveSession session = session(platform);
            scopedPlaylists.put(new ScopedPlaylistKey(AccountScope.fromSession(session), playlist.getSourceRef()), playlist);
        }
    }

    synchronized void removePlaylist(long id) {
        requireCurrentContext();
        Playlist removed = playlists.remove(id);
        if (removed != null) scopedPlaylists.remove(new ScopedPlaylistKey(
                scopeForReference(removed.getSourceRef()), removed.getSourceRef()));
    }

    Playlist toPlaylist(TuneWeavePlatform platform, JsonObject object) {
        String reference = string(object, "ref");
        if (reference == null || reference.isBlank()) reference = string(object, "reference");
        if (reference == null || reference.isBlank()) return Playlist.EMPTY;

        JsonObject creatorObject = object.has("creator") && object.get("creator").isJsonObject()
                ? object.getAsJsonObject("creator") : new JsonObject();
        String creatorRef = string(creatorObject, "ref");
        TuneWeaveSession session = session(platform);
        String creatorUserId = TuneWeaveIdentity.userIdFromReference(platform, creatorRef);
        boolean currentUser = session != null && (creatorObject.isEmpty()
                || creatorUserId.equals(TuneWeaveIdentity.userIdFromReference(platform, session.userId())));
        Profile creator = currentUser
                ? session.toMusicHudProfile()
                : new Profile(string(creatorObject, "name", ""), "",
                stableId(platform, creatorUserId), VipType.NORMAL);
        String cover = imageUrl(object, "cover_url", "pic_url", "cover");
        Playlist playlist = Playlist.fromTuneWeave(
                stableId(platform, "playlist:" + reference), reference,
                string(object, "name", reference), cover.isBlank() ? MusicHud.ICON_BASE64 : cover,
                integer(object, "track_count", integer(object, "item_count", 0)),
                integer(object, "play_count", 0), creator);
        playlist.setPrivacy(playlistPrivacy(object));
        cachePlaylist(playlist);
        return playlist;
    }

    static indi.mopelotus.musichud.beans.music.Privacy playlistPrivacy(JsonObject value) {
        JsonElement visibility = value.get("visibility");
        if (visibility != null && !visibility.isJsonNull()) {
            if (!visibility.isJsonPrimitive() || !visibility.getAsJsonPrimitive().isString()
                    || !"public".equals(visibility.getAsString())) return indi.mopelotus.musichud.beans.music.Privacy.PRIVATE;
        }
        JsonElement raw = value.get("extensions");
        if (raw == null || raw.isJsonNull()) return indi.mopelotus.musichud.beans.music.Privacy.PUBLIC;
        if (!raw.isJsonObject()) return indi.mopelotus.musichud.beans.music.Privacy.PRIVATE;
        JsonObject extensions = raw.getAsJsonObject();
        JsonElement privateFlag = extensions.get("private");
        if (privateFlag != null && !privateFlag.isJsonNull()) {
            if (!privateFlag.isJsonPrimitive() || !privateFlag.getAsJsonPrimitive().isBoolean() || privateFlag.getAsBoolean())
                return indi.mopelotus.musichud.beans.music.Privacy.PRIVATE;
        }
        JsonElement privacy = extensions.get("privacy");
        if (privacy == null || privacy.isJsonNull()) return indi.mopelotus.musichud.beans.music.Privacy.PUBLIC;
        try {
            if (privacy.isJsonPrimitive() && privacy.getAsJsonPrimitive().isNumber() && privacy.getAsBigDecimal().intValueExact() == 0)
                return indi.mopelotus.musichud.beans.music.Privacy.PUBLIC;
        } catch (ArithmeticException | NumberFormatException ignored) { }
        return indi.mopelotus.musichud.beans.music.Privacy.PRIVATE;
    }

    synchronized Album toAlbum(TuneWeavePlatform platform, JsonObject object) {
        requireCurrentContext();
        String reference = string(object, "ref", string(object, "reference", ""));
        if (reference.isBlank()) return Album.NONE;
        LinkedHashSet<Artist> albumArtists = new LinkedHashSet<>();
        JsonElement artistData = object.get("artists");
        if (artistData != null && artistData.isJsonArray()) {
            artistData.getAsJsonArray().forEach(value -> {
                if (value.isJsonObject()) albumArtists.add(toArtist(platform, value.getAsJsonObject()));
            });
        }
        Album album = new Album(stableId(platform, "album:" + reference),
                string(object, "name", reference),
                imageUrl(object, "cover_url", "pic_url", "cover_pic_url", "pic"),
                string(object, "kind", ""), string(object, "company", ""),
                integer(object, "track_count", 0), new ObservableSequencedSet<>(), albumArtists,
                PusherInfo.EMPTY, reference);
        albums.put(album.getId(), album);
        scopedAlbums.put(new ScopedReferenceKey(AccountScope.fromSession(session(platform)), reference), album);
        return album;
    }

    synchronized Artist toArtist(TuneWeavePlatform platform, JsonObject object) {
        requireCurrentContext();
        String reference = string(object, "ref", string(object, "reference", ""));
        String name = string(object, "name", reference);
        Artist artist = new Artist(stableId(platform,
                "artist:" + (reference.isBlank() ? name : reference)), name,
                string(object, "avatar_url", string(object, "cover_url", "")),
                integer(object, "album_count", integer(object, "album_size", 0)),
                integer(object, "music_count", integer(object, "music_size", 0)),
                string(object, "description", ""), new ArrayList<>(),
                integer(object, "total_music_count", integer(object, "music_count", 0)), reference);
        if (!reference.isBlank()) artists.put(artist.getId(), artist);
        if (!reference.isBlank()) scopedArtists.put(new ScopedReferenceKey(AccountScope.fromSession(session(platform)), reference), artist);
        return artist;
    }

    MusicDetail toTrack(TuneWeavePlatform platform, JsonObject input) {
        return toTrack(platform, input, true);
    }

    private MusicDetail toTrack(TuneWeavePlatform platform, JsonObject input, boolean cache) {
        JsonObject object = mergeSnapshot(input);
        String reference = string(object, "ref", string(object, "reference", ""));
        if (reference.isBlank()) return MusicDetail.NONE;

        List<Artist> trackArtists = new ArrayList<>();
        JsonElement artistData = object.get("artists");
        if (artistData != null && artistData.isJsonArray()) {
            artistData.getAsJsonArray().forEach(value -> {
                if (value.isJsonObject()) {
                    trackArtists.add(toArtist(platform, value.getAsJsonObject()));
                } else if (value.isJsonPrimitive()) {
                    String name = value.getAsString();
                    if (!name.isBlank()) {
                        trackArtists.add(new Artist(stableId(platform, "artist-name:" + name), name,
                                "", 0, 0, "", new ArrayList<>(), 0, ""));
                    }
                }
            });
        }

        Album album = Album.NONE;
        JsonElement albumData = object.get("album");
        if (albumData != null && albumData.isJsonObject()) {
            album = toAlbum(platform, albumData.getAsJsonObject());
        }
        if (album == Album.NONE) {
            String cover = imageUrl(object, "cover_url", "pic_url", "cover_pic_url", "pic");
            String albumName = albumData != null && albumData.isJsonPrimitive()
                    ? albumData.getAsString()
                    : string(object, "album_name", string(object, "name", reference));
            if (!cover.isBlank() || !albumName.isBlank()) {
                album = new Album(stableId(platform, "track-album:" + reference), albumName,
                        cover.isBlank() ? MusicHud.ICON_BASE64 : cover, "", "", 0,
                        new ObservableSequencedSet<>(), new LinkedHashSet<>(trackArtists),
                        PusherInfo.EMPTY, "");
            }
        }

        MusicDetail result = MusicDetail.fromTuneWeave(stableId(platform, "track:" + reference),
                reference, "video".equals(string(object, "kind", "track")) ? "video" : "track",
                string(object, "name", string(object, "title", reference)),
                integer(object, "duration_ms", 0), album, trackArtists);
        result.setPusherInfo(PusherInfo.EMPTY);
        if (cache) cacheTrack(result);
        return result;
    }

    TuneWeaveCloudTrack toCloudTrack(TuneWeavePlatform platform, JsonObject object) {
        String reference = string(object, "ref", "");
        if (!reference.startsWith(platform.apiName() + ":") || reference.length() > 512)
            throw new IllegalArgumentException("Invalid cloud reference");
        JsonObject trackData = object.has("track") && object.get("track").isJsonObject()
                ? object.getAsJsonObject("track") : object;
        MusicDetail track = toTrack(platform, trackData, false);
        if (track == MusicDetail.NONE) throw new IllegalArgumentException("Cloud track snapshot is missing identity");
        track.setExtraInfo(new MusicDetail.ExtraInfo(true, 0, false));
        track.setSourcePartRef(reference);
        track.setCloudSource(true);
        return new TuneWeaveCloudTrack(reference, track,
                string(object, "filename", ""), longValue(object, "file_size", 0L),
                string(object, "file_type", ""), longValue(object, "bitrate", 0L),
                string(object, "md5", ""), string(object, "added_at", ""),
                string(object, "matched_track_ref", ""));
    }

    TuneWeavePodcast toPodcast(TuneWeavePlatform platform, JsonObject object) {
        String reference = string(object, "ref", string(object, "reference", ""));
        JsonObject creator = unwrap(object.get("creator"));
        return new TuneWeavePodcast(reference, string(object, "name", reference),
                string(object, "description", ""), string(object, "cover_url", ""),
                string(creator, "name", ""), string(object, "category", ""),
                string(object, "secondary_category", ""), longValue(object, "episode_count", 0),
                longValue(object, "subscriber_count", 0), longValue(object, "play_count", 0),
                bool(object, "subscribed", false));
    }

    TuneWeavePodcastEpisode toPodcastEpisode(TuneWeavePlatform platform, JsonObject object) {
        String reference = string(object, "ref", string(object, "reference", ""));
        JsonObject creator = unwrap(object.get("creator"));
        JsonObject audio = unwrap(object.get("audio"));
        return new TuneWeavePodcastEpisode(reference,
                referenceValue(object.get("podcast_ref")), string(object, "name", reference),
                string(object, "description", ""), string(object, "cover_url", ""),
                string(creator, "name", ""), string(audio, "ref", ""),
                integer(object, "duration_ms", integer(audio, "duration_ms", 0)),
                string(object, "published_at", ""), longValue(object, "serial_number", 0),
                bool(object, "has_lyrics", false));
    }

    List<TuneWeaveRadioOption> toRadioOptions(JsonElement element) {
        List<TuneWeaveRadioOption> result = new ArrayList<>();
        for (JsonElement value : elements(element)) {
            JsonObject option = unwrap(value);
            String id = string(option, "id", "");
            if (!id.isBlank()) {
                result.add(new TuneWeaveRadioOption(id, string(option, "name", id)));
            }
        }
        return result;
    }

    TuneWeaveRadioStation toRadioStation(
            TuneWeavePlatform platform, JsonObject object, String fallbackCategory) {
        String reference = string(object, "ref", string(object, "reference", ""));
        return new TuneWeaveRadioStation(reference,
                string(object, "name", reference), string(object, "description", ""),
                string(object, "cover_url", ""), string(object, "category", fallbackCategory),
                string(object, "region", ""), string(object, "current_program", ""),
                bool(object, "subscribed", false));
    }

    MusicDetail toVideoTrack(TuneWeavePlatform platform, JsonObject object) {
        String reference = string(object, "ref", string(object, "reference", ""));
        if (reference.isBlank()) return MusicDetail.NONE;
        JsonObject snapshot = object.has("snapshot") && object.get("snapshot").isJsonObject()
                ? object.getAsJsonObject("snapshot") : new JsonObject();
        List<Artist> creators = videoCreatorInfos(object, snapshot).stream()
                .map(creator -> videoCreatorArtist(platform, creator)).toList();
        String title = string(object, "title",
                string(object, "name", string(snapshot, "title", reference)));
        String coverUrl = string(object, "cover_url", string(snapshot, "cover_url", ""));
        Album album = videoAlbum(platform, reference, title, coverUrl, creators);
        MusicDetail result = MusicDetail.fromTuneWeave(stableId(platform, "video:" + reference),
                reference, "video", title,
                integer(object, "duration_ms", integer(snapshot, "duration_ms", 0)), album, creators);
        result.setPusherInfo(PusherInfo.EMPTY);
        cacheTrack(result);
        return result;
    }

    TuneWeaveVideo toVideoInfo(JsonObject object) {
        String reference = string(object, "ref", "");
        List<TuneWeaveVideoCreator> creators =
                videoCreatorInfos(object, new JsonObject());
        JsonObject extensions = object.has("extensions") && object.get("extensions").isJsonObject()
                ? object.getAsJsonObject("extensions") : new JsonObject();
        return new TuneWeaveVideo(reference, string(object, "title", reference),
                string(object, "description", ""), string(object, "cover_url", ""),
                integer(object, "duration_ms", 0), string(object, "published_at", ""),
                longValue(object, "play_count", 0), creators, integer(extensions, "part_count", 1));
    }

    List<TuneWeaveVideoCreator> videoCreatorInfos(
            JsonObject object, JsonObject snapshot) {
        LinkedHashMap<String, TuneWeaveVideoCreator> creators = new LinkedHashMap<>();
        JsonElement creatorData = object.get("creators");
        if (creatorData != null && creatorData.isJsonArray()) {
            creatorData.getAsJsonArray().forEach(value -> {
                if (value.isJsonObject()) {
                    JsonObject creator = value.getAsJsonObject();
                    addVideoCreator(creators, string(creator, "ref", ""),
                            string(creator, "name", ""), string(creator, "avatar_url", ""));
                } else if (value.isJsonPrimitive()) {
                    addVideoCreator(creators, "", value.getAsString(), "");
                }
            });
        }
        if (creators.isEmpty()) {
            for (String key : List.of("creator", "owner")) {
                JsonElement value = object.get(key);
                if (value != null && value.isJsonObject()) {
                    JsonObject creator = value.getAsJsonObject();
                    addVideoCreator(creators, string(creator, "ref", ""),
                            string(creator, "name", ""), string(creator, "avatar_url", ""));
                }
            }
        }
        if (creators.isEmpty()) {
            String uploader = string(object, "uploader", "");
            if (!uploader.isBlank()) addVideoCreator(creators, "", uploader, "");
        }
        if (creators.isEmpty() && snapshot.has("artists") && snapshot.get("artists").isJsonArray()) {
            snapshot.getAsJsonArray("artists").forEach(value -> {
                if (value.isJsonPrimitive()) addVideoCreator(creators, "", value.getAsString(), "");
            });
        }
        return List.copyOf(creators.values());
    }

    Artist videoCreatorArtist(
            TuneWeavePlatform platform, TuneWeaveVideoCreator creator) {
        String identity = creator.reference().isBlank() ? creator.name() : creator.reference();
        return new Artist(stableId(platform, "video-creator:" + identity), creator.name(),
                creator.avatarUrl(), 0, 0, "", new ArrayList<>(), 0, creator.reference());
    }

    Album videoAlbum(TuneWeavePlatform platform, String reference, String title,
                     String coverUrl, List<Artist> creators) {
        return new Album(stableId(platform, "video-album:" + reference), title,
                coverUrl == null || coverUrl.isBlank() ? MusicHud.ICON_BASE64 : coverUrl,
                "Video", "Bilibili", 1, new ObservableSequencedSet<>(),
                new LinkedHashSet<>(creators), PusherInfo.EMPTY, "");
    }

    long stableId(TuneWeavePlatform platform, String value) {
        return TuneWeaveIdentity.stableId(platform, value);
    }

    private static void addVideoCreator(
            Map<String, TuneWeaveVideoCreator> creators,
            String reference, String name, String avatarUrl) {
        if (name == null || name.isBlank()) return;
        String identity = reference == null || reference.isBlank() ? "name:" + name : reference;
        creators.putIfAbsent(identity, new TuneWeaveVideoCreator(
                Objects.requireNonNullElse(reference, ""), name,
                Objects.requireNonNullElse(avatarUrl, "")));
    }

    private record ScopedPlaylistKey(AccountScope scope, String reference) {
        private ScopedPlaylistKey {
            Objects.requireNonNull(scope);
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference");
        }
    }

    private record ScopedTrackKey(AccountScope scope, String reference) {
        private ScopedTrackKey {
            Objects.requireNonNull(scope);
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference");
        }
    }

    private record ScopedReferenceKey(AccountScope scope, String reference) {
        private ScopedReferenceKey {
            Objects.requireNonNull(scope);
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("reference");
        }
    }
}
