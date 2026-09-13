package indi.mopelotus.musichud.server.api.impl.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.beans.music.*;
import indi.mopelotus.musichud.beans.music.actions.SubscribableType;
import indi.mopelotus.musichud.beans.music.actions.SubscribeAction;
import indi.mopelotus.musichud.beans.user.Profile;
import indi.mopelotus.musichud.beans.user.VipType;
import indi.mopelotus.musichud.server.api.IMusicApiService;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.utils.JsonUtil;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Strongly typed bridge from TuneWeave resources to Music HUD's wire model. */
public final class TuneWeaveMusicApiService implements IMusicApiService {
    private static final String DEFAULT_PLATFORM = "netease";
    private static volatile TuneWeaveMusicApiService instance;

    private final Map<Long, String> refsById = new ConcurrentHashMap<>();
    private final Map<Long, MusicDetail> tracksById = new ConcurrentHashMap<>();
    private final Map<Long, Playlist> playlistsById = new ConcurrentHashMap<>();
    private final Map<Long, Album> albumsById = new ConcurrentHashMap<>();
    private final Map<Long, Artist> artistsById = new ConcurrentHashMap<>();

    private TuneWeaveMusicApiService() {
    }

    public static TuneWeaveMusicApiService getInstance() {
        if (instance == null) {
            synchronized (TuneWeaveMusicApiService.class) {
                if (instance == null) {
                    instance = new TuneWeaveMusicApiService();
                }
            }
        }
        return instance;
    }

    public List<MusicDetail> searchMusic(String keywords, int offset, String platform) {
        return searchItems(keywords, "track", offset, platform).stream().map(this::toTrack).toList();
    }

    public List<Album> searchAlbums(String keywords, int offset, String platform) {
        return searchItems(keywords, "album", offset, platform).stream().map(this::toAlbum).toList();
    }

    public List<Artist> searchArtists(String keywords, int offset, String platform) {
        return searchItems(keywords, "artist", offset, platform).stream().map(this::toArtist).toList();
    }

    public List<Playlist> searchPlaylists(String keywords, int offset, String platform) {
        return searchItems(keywords, "playlist", offset, platform).stream().map(this::toPlaylist).toList();
    }

    @Override
    public List<MusicDetail> searchMusic(String keywords, int offset) {
        return searchMusic(keywords, offset, "all");
    }

    @Override
    public List<Album> searchAlbums(String keywords, int offset) {
        return searchAlbums(keywords, offset, "all");
    }

    @Override
    public List<Artist> searchArtists(String keywords, int offset) {
        return searchArtists(keywords, offset, "all");
    }

    @Override
    public List<Playlist> searchPlaylists(String keywords, int offset) {
        return searchPlaylists(keywords, offset, "all");
    }

    private List<JsonObject> searchItems(String keywords, String type, int offset, String platform) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", keywords);
        query.put("type", type);
        query.put("platform", platform == null || platform.isBlank() ? "all" : platform);
        query.put("limit", "50");
        query.put("offset", Integer.toString(Math.max(0, offset)));
        JsonElement data;
        try {
            data = TuneWeaveApiClient.get("/v1/search", query);
        } catch (TuneWeaveApiClient.TuneWeaveException error) {
            if (!"all".equals(query.get("platform"))) {
                throw error;
            }
            query.put("platform", DEFAULT_PLATFORM);
            data = TuneWeaveApiClient.get("/v1/search", query);
        }
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement item : elements(data)) {
            JsonObject object = unwrap(item);
            if (!object.isEmpty()) {
                result.add(object);
            }
        }
        return result;
    }

    @Override
    public Playlist getPlaylistDetail(long id, boolean ignoreCache, @Nullable UUID player) {
        Playlist brief = playlistsById.get(id);
        String reference = brief == null ? refsById.get(id) : brief.getSourceRef();
        if (reference == null || reference.isBlank()) {
            return brief == null ? Playlist.empty(id) : brief;
        }
        if (reference.startsWith("account:favorite_tracks:")) {
            Playlist liked = brief == null ? Playlist.empty(id) : brief;
            liked.setTracks(observable(accountTracks(player)));
            liked.setMusicTrackCount(liked.getTracks().size());
            return liked;
        }
        Playlist playlist = toPlaylist(TuneWeaveApiClient.get(
                "/v1/playlists/" + TuneWeaveApiClient.encodePathSegment(reference), Map.of()));
        List<MusicDetail> tracks = new ArrayList<>();
        for (JsonElement element : elements(TuneWeaveApiClient.get(
                "/v1/playlists/" + TuneWeaveApiClient.encodePathSegment(reference) + "/tracks",
                Map.of("limit", "100", "offset", "0")))) {
            tracks.add(toTrack(unwrap(element)));
        }
        playlist.setTracks(observable(tracks));
        playlist.setMusicTrackCount(tracks.size());
        playlistsById.put(id, playlist);
        return playlist;
    }

    @Override
    public Album getAlbumInfoDetail(long id, boolean ignoreCache, UUID playerUUID) {
        String reference = refsById.get(id);
        Album cached = albumsById.getOrDefault(id, Album.NONE);
        if (reference == null || reference.isBlank()) {
            return cached;
        }
        Album album = toAlbum(TuneWeaveApiClient.get(
                "/v1/albums/" + TuneWeaveApiClient.encodePathSegment(reference), Map.of()));
        List<MusicDetail> tracks = new ArrayList<>();
        for (JsonElement item : elements(TuneWeaveApiClient.get(
                "/v1/albums/" + TuneWeaveApiClient.encodePathSegment(reference) + "/tracks",
                pageQuery(playerUUID)))) {
            tracks.add(toTrack(unwrap(item)));
        }
        album.setMusicDetails(observable(tracks));
        return album;
    }

    @Override
    public Artist getArtistDetail(long id, UUID playerUUID) {
        String reference = refsById.get(id);
        if (reference == null || reference.isBlank()) {
            return artistsById.getOrDefault(id, new Artist());
        }
        return toArtist(TuneWeaveApiClient.get(
                "/v1/artists/" + TuneWeaveApiClient.encodePathSegment(reference), providerAccountQuery(playerUUID)));
    }

    @Override
    public List<MusicDetail> getArtistMoreMusic(long id, int offset, UUID playerUUID) {
        String reference = refsById.get(id);
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        Map<String, String> query = providerAccountQuery(playerUUID);
        query.put("limit", "50");
        query.put("offset", Integer.toString(Math.max(0, offset)));
        return elements(TuneWeaveApiClient.get(
                "/v1/artists/" + TuneWeaveApiClient.encodePathSegment(reference) + "/tracks", query))
                .stream().map(this::unwrap).map(this::toTrack).toList();
    }

    @Override
    public List<MusicDetail> getMusicDetailByIds(List<Long> ids, UUID playerUUID) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<MusicDetail> result = new ArrayList<>();
        for (Long id : ids) {
            MusicDetail cached = tracksById.get(id);
            if (cached != null) {
                result.add(cached);
                continue;
            }
            String reference = refsById.get(id);
            if (reference != null) {
                result.add(toTrack(TuneWeaveApiClient.get(
                        "/v1/tracks/" + TuneWeaveApiClient.encodePathSegment(reference), providerAccountQuery(playerUUID))));
            }
        }
        return result;
    }

    @Override
    public <T> T search(String keywords, int offset, int limit, SearchType searchType,
                        Function<String, T> transformer) {
        JsonElement data = TuneWeaveApiClient.get("/v1/search", Map.of(
                "q", keywords,
                "type", searchTypeName(searchType),
                "platform", "all",
                "limit", Integer.toString(Math.max(1, Math.min(100, limit))),
                "offset", Integer.toString(Math.max(0, offset))));
        return transformer.apply(data.toString());
    }

    @Override
    public MusicResourceInfo getResourceInfo(MusicDetail musicDetail, Quality quality, UUID playerUUID) {
        if (musicDetail == null || musicDetail == MusicDetail.NONE) {
            return MusicResourceInfo.NONE;
        }
        String reference = musicDetail.getSourceRef();
        if (reference.isBlank()) {
            reference = DEFAULT_PLATFORM + ':' + musicDetail.getId();
        }
        boolean video = "video".equals(musicDetail.getSourceKind());
        String path = video
                ? "/v1/videos/" + TuneWeaveApiClient.encodePathSegment(reference) + "/audio-stream"
                : "/v1/tracks/" + TuneWeaveApiClient.encodePathSegment(reference) + "/stream";
        Map<String, String> query = providerAccountQuery(playerUUID);
        query.put("quality", qualityName(quality));
        if (video) {
            query.put("type", "video");
            if (!musicDetail.getSourcePartRef().isBlank()) {
                query.put("part", musicDetail.getSourcePartRef());
            }
        }
        JsonObject stream = unwrap(TuneWeaveApiClient.get(path, query));
        String url = string(stream, "url", "");
        if (url.isBlank()) {
            return MusicResourceInfo.NONE;
        }
        return new MusicResourceInfo(
                musicDetail.getId(), url, integer(stream, "bitrate", 0), longValue(stream, "size", 0L),
                FormatType.fromSerializedName(string(stream, "format", string(stream, "codec", ""))),
                "", Fee.UNSET, integer(stream, "duration_ms", musicDetail.getDurationMillis()),
                stringMap(stream.get("headers")), stringList(stream.get("backup_urls")));
    }

    @Override
    public UserCategoryPlaylists getPlayersUserPlaylists(boolean ignoreCache, UUID playerUUID) {
        List<Playlist> all = elements(TuneWeaveApiClient.get(
                "/v1/account/playlists", pageAccountQuery(playerUUID))).stream()
                .map(this::toPlaylist).filter(playlist -> playlist != Playlist.EMPTY).toList();
        ObservableSequencedSet<Playlist> created = new ObservableSequencedSet<>();
        ObservableSequencedSet<Playlist> subscribed = new ObservableSequencedSet<>();
        for (Playlist playlist : all) {
            JsonObject raw = findPlaylistRaw(playlist.getSourceRef(), playerUUID);
            boolean isSubscribed = bool(raw, "subscribed", false);
            (isSubscribed ? subscribed : created).add(playlist);
        }
        String likeRef = "account:favorite_tracks:" + DEFAULT_PLATFORM;
        long likeId = cacheId("playlist", likeRef + ':' + playerAccount(playerUUID));
        Playlist liked = Playlist.fromTuneWeave(
                likeId, likeRef, "Liked Songs", MusicHud.ICON_BASE64, 0, 0, Profile.ANONYMOUS);
        playlistsById.put(likeId, liked);
        return new UserCategoryPlaylists(liked, created, subscribed);
    }

    @Override
    public LinkedHashSet<Album> getPlayersUserSubscribedAlbums(boolean ignoreCache, UUID playerUUID) {
        LinkedHashSet<Album> result = new LinkedHashSet<>();
        for (JsonElement item : elements(TuneWeaveApiClient.get(
                "/v1/account/library/albums", pageAccountQuery(playerUUID)))) {
            Album album = toAlbum(item);
            if (album != Album.NONE) {
                result.add(album);
            }
        }
        return result;
    }

    @Override
    public LinkedHashSet<Artist> getPlayersUserSubscribedArtists(boolean ignoreCache, UUID playerUUID) {
        LinkedHashSet<Artist> result = new LinkedHashSet<>();
        for (JsonElement item : elements(TuneWeaveApiClient.get(
                "/v1/account/following/artists", pageAccountQuery(playerUUID)))) {
            Artist artist = toArtist(item);
            if (!artist.getSourceRef().isBlank()) {
                result.add(artist);
            }
        }
        return result;
    }

    @Override
    public LyricInfo getLyricInfo(MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail.getSourceRef().isBlank()
                || "video".equals(musicDetail.getSourceKind())) {
            return LyricInfo.NONE;
        }
        JsonObject lyrics = unwrap(TuneWeaveApiClient.get(
                "/v1/tracks/" + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()) + "/lyrics",
                Map.of("word_synced", "true", "translated", "true", "romanized", "true")));
        return new LyricInfo(
                new Lyric(string(lyrics, "plain", "")),
                new Lyric(string(lyrics, "translated", "")),
                new Lyric(string(lyrics, "word_synced", "")),
                new Lyric(string(lyrics, "romanized", "")));
    }

    @Override
    public void addToPlaylist(long playlistId, long musicId, UUID uuid) {
        mutatePlaylist(playlistId, musicId, uuid, true);
    }

    @Override
    public void removeFromPlaylist(long playlistId, long musicId, UUID uuid) {
        mutatePlaylist(playlistId, musicId, uuid, false);
    }

    @Override
    public void userSubscribe(long id, SubscribableType type, SubscribeAction action, UUID playerUUID) {
        String reference = refsById.get(id);
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Unknown TuneWeave resource: " + id);
        }
        String base = switch (type) {
            case ALBUM -> "/v1/account/library/albums/";
            case ARTIST -> "/v1/account/following/artists/";
            case PLAYLIST -> "/v1/account/favorites/playlists/";
        };
        String path = base + TuneWeaveApiClient.encodePathSegment(reference);
        Map<String, String> query = providerAccountQuery(playerUUID);
        if (action == SubscribeAction.SUBSCRIBE) {
            TuneWeaveApiClient.put(path, query, null);
        } else {
            TuneWeaveApiClient.delete(path, query, null);
        }
    }

    private void mutatePlaylist(long playlistId, long musicId, UUID uuid, boolean add) {
        String playlistRef = refsById.get(playlistId);
        String trackRef = refsById.get(musicId);
        if (playlistRef == null || trackRef == null) {
            throw new IllegalArgumentException("Unknown TuneWeave playlist or track reference");
        }
        JsonObject body = new JsonObject();
        JsonArray refs = new JsonArray();
        refs.add(trackRef);
        body.add("refs", refs);
        body.addProperty("account", playerAccount(uuid));
        String path = "/v1/playlists/" + TuneWeaveApiClient.encodePathSegment(playlistRef) + "/tracks";
        if (add) {
            TuneWeaveApiClient.post(path, Map.of(), body);
        } else {
            TuneWeaveApiClient.delete(path, Map.of(), body);
        }
    }

    private List<MusicDetail> accountTracks(UUID playerUUID) {
        List<MusicDetail> result = new ArrayList<>();
        for (JsonElement item : elements(TuneWeaveApiClient.get(
                "/v1/account/favorites/tracks", pageAccountQuery(playerUUID)))) {
            MusicDetail detail = toTrack(item);
            if (detail != MusicDetail.NONE) {
                result.add(detail);
            }
        }
        return result;
    }

    private JsonObject findPlaylistRaw(String reference, UUID playerUUID) {
        try {
            return unwrap(TuneWeaveApiClient.get(
                    "/v1/playlists/" + TuneWeaveApiClient.encodePathSegment(reference),
                    providerAccountQuery(playerUUID)));
        } catch (RuntimeException ignored) {
            return new JsonObject();
        }
    }

    private MusicDetail toTrack(JsonElement element) {
        return toTrack(unwrap(element));
    }

    private MusicDetail toTrack(JsonObject object) {
        String reference = reference(object);
        if (reference.isBlank()) {
            return MusicDetail.NONE;
        }
        long id = cacheId("track", reference);
        List<Artist> artists = new ArrayList<>();
        JsonElement artistData = object.get("artists");
        if (artistData != null && artistData.isJsonArray()) {
            for (JsonElement artist : artistData.getAsJsonArray()) {
                if (artist.isJsonObject()) {
                    artists.add(toArtistSummary(artist.getAsJsonObject(), reference));
                }
            }
        }
        Album album = Album.NONE;
        JsonElement albumData = object.get("album");
        if (albumData != null && albumData.isJsonObject()) {
            JsonObject albumObject = albumData.getAsJsonObject();
            String albumRef = string(albumObject, "ref", "");
            if (!albumRef.isBlank() || !string(albumObject, "name", "").isBlank()) {
                album = new Album(
                        cacheId("album", albumRef.isBlank() ? reference + ":album" : albumRef),
                        string(albumObject, "name", ""),
                        string(albumObject, "cover_url", ""),
                        "", "", 0, new ObservableSequencedSet<>(), new LinkedHashSet<>(artists),
                        PusherInfo.EMPTY, albumRef);
                albumsById.put(album.getId(), album);
            }
        }
        String kind = string(object, "kind", string(object, "type", "track"));
        MusicDetail detail = MusicDetail.fromTuneWeave(
                id, reference, "video".equals(kind) ? "video" : "track",
                string(object, "name", string(object, "title", reference)),
                integer(object, "duration_ms", 0), album, artists);
        detail.setPusherInfo(PusherInfo.EMPTY);
        tracksById.put(id, detail);
        return detail;
    }

    private Playlist toPlaylist(JsonElement element) {
        return toPlaylist(unwrap(element));
    }

    private Playlist toPlaylist(JsonObject object) {
        String reference = reference(object);
        if (reference.isBlank()) {
            return Playlist.EMPTY;
        }
        long id = cacheId("playlist", reference);
        JsonObject creatorObject = object.has("creator") && object.get("creator").isJsonObject()
                ? object.getAsJsonObject("creator") : new JsonObject();
        String creatorRef = string(creatorObject, "ref", "");
        Profile creator = new Profile(
                string(creatorObject, "name", ""), "", cacheId("user", creatorRef), VipType.NORMAL);
        Playlist playlist = Playlist.fromTuneWeave(
                id, reference, string(object, "name", reference), string(object, "cover_url", ""),
                integer(object, "track_count", integer(object, "item_count", 0)),
                extensionInt(object, "play_count"), creator);
        playlistsById.put(id, playlist);
        return playlist;
    }

    private Album toAlbum(JsonElement element) {
        return toAlbum(unwrap(element));
    }

    private Album toAlbum(JsonObject object) {
        String reference = reference(object);
        if (reference.isBlank()) {
            return Album.NONE;
        }
        long id = cacheId("album", reference);
        LinkedHashSet<Artist> artists = new LinkedHashSet<>();
        JsonElement artistData = object.get("artists");
        if (artistData != null && artistData.isJsonArray()) {
            for (JsonElement artist : artistData.getAsJsonArray()) {
                if (artist.isJsonObject()) {
                    artists.add(toArtistSummary(artist.getAsJsonObject(), reference));
                }
            }
        }
        Album album = new Album(
                id,
                string(object, "name", reference),
                string(object, "cover_url", ""),
                string(object, "kind", ""),
                string(object, "company", ""),
                integer(object, "track_count", 0),
                new ObservableSequencedSet<>(), artists, PusherInfo.EMPTY, reference);
        albumsById.put(id, album);
        return album;
    }

    private Artist toArtist(JsonElement element) {
        return toArtist(unwrap(element));
    }

    private Artist toArtist(JsonObject object) {
        String reference = reference(object);
        if (reference.isBlank()) {
            return new Artist();
        }
        long id = cacheId("artist", reference);
        Artist artist = new Artist(
                id,
                string(object, "name", reference),
                string(object, "avatar_url", string(object, "cover_url", "")),
                integer(object, "album_count", 0),
                integer(object, "track_count", 0),
                string(object, "description", ""),
                new ArrayList<>(),
                integer(object, "track_count", 0),
                reference);
        artistsById.put(id, artist);
        return artist;
    }

    private Artist toArtistSummary(JsonObject object, String parentReference) {
        String reference = string(object, "ref", "");
        String name = string(object, "name", "");
        String identity = reference.isBlank() ? parentReference + ":artist:" + name : reference;
        Artist artist = new Artist(cacheId("artist", identity), name, "", 0, 0, "",
                new ArrayList<>(), 0, reference);
        if (!reference.isBlank()) {
            artistsById.put(artist.getId(), artist);
        }
        return artist;
    }

    private JsonObject unwrap(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return new JsonObject();
        }
        JsonObject object = element.getAsJsonObject();
        return object.has("data") && object.get("data").isJsonObject()
                ? object.getAsJsonObject("data") : object;
    }

    private static List<JsonElement> elements(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return List.of();
        }
        if (value.isJsonArray()) {
            List<JsonElement> result = new ArrayList<>();
            value.getAsJsonArray().forEach(result::add);
            return result;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            for (String key : List.of("items", "tracks", "playlists", "albums", "artists", "results")) {
                if (object.has(key) && object.get(key).isJsonArray()) {
                    List<JsonElement> result = new ArrayList<>();
                    object.getAsJsonArray(key).forEach(result::add);
                    return result;
                }
            }
        }
        return List.of();
    }

    private static String searchTypeName(SearchType type) {
        return switch (type) {
            case ALBUM -> "album";
            case ARTIST -> "artist";
            case PLAYLIST -> "playlist";
            default -> "track";
        };
    }

    private static Map<String, String> pageQuery(UUID playerUUID) {
        Map<String, String> query = providerAccountQuery(playerUUID);
        query.put("limit", "100");
        query.put("offset", "0");
        return query;
    }

    private static Map<String, String> pageAccountQuery(UUID playerUUID) {
        Map<String, String> query = accountQuery(playerUUID);
        query.put("limit", "100");
        query.put("offset", "0");
        return query;
    }

    private static Map<String, String> accountQuery(UUID playerUUID) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("platform", DEFAULT_PLATFORM);
        query.put("account", playerAccount(playerUUID));
        return query;
    }

    private static Map<String, String> providerAccountQuery(UUID playerUUID) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("account", playerAccount(playerUUID));
        return query;
    }

    public static String playerAccount(UUID playerUUID) {
        return playerUUID == null ? "default" : "minecraft-" + playerUUID.toString().replace("-", "");
    }

    private long cacheId(String namespace, String reference) {
        String identity = namespace + ':' + Objects.requireNonNullElse(reference, "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                value = (value << 8) | (hash[i] & 0xffL);
            }
            value &= Long.MAX_VALUE;
            if (value == 0 || value == -1) {
                value = 1;
            }
            refsById.put(value, reference);
            return value;
        } catch (Exception error) {
            long value = Integer.toUnsignedLong(identity.hashCode()) + 1;
            refsById.put(value, reference);
            return value;
        }
    }

    private static String reference(JsonObject object) {
        String reference = string(object, "ref", "");
        if (!reference.isBlank()) {
            return reference;
        }
        String platform = string(object, "platform", "");
        String id = string(object, "id", "");
        return platform.isBlank() || id.isBlank() ? "" : platform + ':' + id;
    }

    private static String string(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            long number = value.getAsLong();
            return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, number));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int extensionInt(JsonObject object, String key) {
        if (!object.has("extensions") || !object.get("extensions").isJsonObject()) {
            return 0;
        }
        return integer(object.getAsJsonObject("extensions"), key, 0);
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsBoolean();
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Map<String, String> stringMap(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        element.getAsJsonObject().entrySet().forEach(entry -> {
            if (!entry.getValue().isJsonNull()) {
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
        });
        return result;
    }

    private static List<String> stringList(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        return elements(element).stream()
                .filter(value -> value != null && !value.isJsonNull() && value.isJsonPrimitive())
                .map(JsonElement::getAsString)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private static String qualityName(Quality quality) {
        if (quality == null) {
            return "auto";
        }
        return switch (quality) {
            case STANDARD -> "standard";
            case HIGHER -> "higher";
            case EX_HIGH -> "high";
            case LOSSLESS -> "lossless";
            case HIRES -> "hires";
            case JY_EFFECT -> "spatial";
            case SKY -> "surround";
            case DOLBY -> "dolby";
            case JY_MASTER -> "master";
            case VIVID -> "vivid";
            case NONE -> "auto";
        };
    }

    private static <T> ObservableSequencedSet<T> observable(Collection<T> values) {
        ObservableSequencedSet<T> result = new ObservableSequencedSet<>(values == null ? 0 : values.size());
        if (values != null) {
            result.addAll(values);
        }
        return result;
    }
}
