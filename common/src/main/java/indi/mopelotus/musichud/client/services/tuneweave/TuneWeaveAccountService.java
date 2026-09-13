package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.UserCategoryPlaylists;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.bool;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.elements;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.integer;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.object;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.string;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.unwrap;

/** Loads the authenticated user's collections and recommendation queue. */
final class TuneWeaveAccountService {
    static final int FAVORITE_INTELLIGENCE_MAX_RECOMMENDATIONS = 100;
    private final TuneWeaveGateway gateway;
    private final TuneWeaveAuthenticationService authentication;
    private final TuneWeaveEntityMapper entities;
    private final Map<FavoriteKey, String> favoritePlaylistReferences = new ConcurrentHashMap<>();
    private record FavoriteKey(TuneWeavePlatform platform, AccountScope account) {}
    private record PageKey(TuneWeavePlatform platform, AccountScope account, String path) {}
    private final ResumableOffsetCollection<PageKey, JsonElement> pages = new ResumableOffsetCollection<>();

    private FavoriteKey favoriteKey(TuneWeavePlatform platform) {
        return new FavoriteKey(platform, entities.accountScope(platform));
    }

    void clear() { favoritePlaylistReferences.clear(); pages.clear(); }

    TuneWeaveAccountService(TuneWeaveGateway gateway,
                            TuneWeaveAuthenticationService authentication,
                            TuneWeaveEntityMapper entities) {
        this.gateway = Objects.requireNonNull(gateway);
        this.authentication = Objects.requireNonNull(authentication);
        this.entities = Objects.requireNonNull(entities);
    }

    UserCategoryPlaylists loadPlaylists() {
        return loadPlaylists(gateway.defaultPlatform());
    }

    UserCategoryPlaylists loadPlaylists(TuneWeavePlatform platform) {
        return loadPlaylists(platform, false, ignored -> {});
    }

    UserCategoryPlaylists loadPlaylists(TuneWeavePlatform platform, boolean refresh, java.util.function.Consumer<UserCategoryPlaylists> progress) {
        Objects.requireNonNull(platform, "platform");
        if (platform == TuneWeavePlatform.BILIBILI) {
            return loadBilibiliFavoriteFolders(refresh, progress);
        }
        Playlist liked = entities.toPlaylist(platform, unwrap(gateway.requestForPlatform(
                platform, "GET", "/v1/account/favorites/playlist",
                Map.of("platform", platform.apiName()), null).data()));
        if (liked == Playlist.EMPTY) {
            throw new TuneWeaveApiClient.TuneWeaveException(
                    "TuneWeave favorite playlist response did not contain a reference", false);
        }
        favoritePlaylistReferences.put(favoriteKey(platform), liked.getSourceRef());

        var data = loadAccountPages(platform, "/v1/account/playlists", refresh,
                values -> progress.accept(mapPlaylists(platform, liked, values)));
        return mapPlaylists(platform, liked, data);
    }

    private UserCategoryPlaylists mapPlaylists(TuneWeavePlatform platform, Playlist liked, List<JsonElement> data) {
        ObservableSequencedSet<Playlist> created = new ObservableSequencedSet<>();
        ObservableSequencedSet<Playlist> subscribed = new ObservableSequencedSet<>();
        for (JsonElement item : data) {
            JsonObject raw = unwrap(item);
            Playlist playlist = entities.toPlaylist(platform, raw);
            if (playlist == Playlist.EMPTY || liked.getSourceRef().equals(playlist.getSourceRef())) {
                continue;
            }
            (bool(raw, "subscribed", false) ? subscribed : created).add(playlist);
        }
        return new UserCategoryPlaylists(liked, created, subscribed);
    }

    LinkedHashSet<Album> loadAlbums() {
        return loadAlbums(gateway.defaultPlatform());
    }

    LinkedHashSet<Album> loadAlbums(TuneWeavePlatform platform) {
        return loadAlbums(platform, false, ignored -> {});
    }

    LinkedHashSet<Album> loadAlbums(TuneWeavePlatform platform, boolean refresh, java.util.function.Consumer<LinkedHashSet<Album>> progress) {
        return mapAlbums(platform, loadAccountPages(platform, "/v1/account/library/albums", refresh,
                data -> progress.accept(mapAlbums(platform, data))));
    }

    private LinkedHashSet<Album> mapAlbums(TuneWeavePlatform platform, List<JsonElement> data) {
        LinkedHashSet<Album> result = new LinkedHashSet<>();
        for (JsonElement item : data) {
            Album album = entities.toAlbum(platform, unwrap(item));
            if (album != Album.NONE) {
                result.add(album);
            }
        }
        return result;
    }

    LinkedHashSet<Artist> loadArtists() {
        return loadArtists(gateway.defaultPlatform());
    }

    LinkedHashSet<Artist> loadArtists(TuneWeavePlatform platform) {
        return loadArtists(platform, false, ignored -> {});
    }

    LinkedHashSet<Artist> loadArtists(TuneWeavePlatform platform, boolean refresh, java.util.function.Consumer<LinkedHashSet<Artist>> progress) {
        return mapArtists(platform, loadAccountPages(platform, "/v1/account/following/artists", refresh,
                data -> progress.accept(mapArtists(platform, data))));
    }

    private LinkedHashSet<Artist> mapArtists(TuneWeavePlatform platform, List<JsonElement> data) {
        LinkedHashSet<Artist> result = new LinkedHashSet<>();
        for (JsonElement item : data) {
            Artist artist = entities.toArtist(platform, unwrap(item));
            if (!artist.getSourceRef().isBlank()) {
                result.add(artist);
            }
        }
        return result;
    }

    private List<JsonElement> loadAllAccountPages(TuneWeavePlatform platform, String path) {
        return OffsetPagination.loadAll(offset -> gateway.requestForPlatform(platform, "GET", path,
                Map.of("platform", platform.apiName(), "limit", "100", "offset", Integer.toString(offset)), null));
    }

    private List<JsonElement> loadAccountPages(TuneWeavePlatform platform, String path, boolean refresh,
                                               java.util.function.Consumer<List<JsonElement>> progress) {
        return pages.load(new PageKey(platform, entities.accountScope(platform), path), refresh,
                offset -> gateway.requestForPlatform(platform, "GET", path,
                        Map.of("platform", platform.apiName(), "limit", "100", "offset", Integer.toString(offset)), null),
                JsonElement::deepCopy, value -> string(unwrap(value), "ref", string(unwrap(value), "reference", "")),
                snapshot -> progress.accept(snapshot.items())).items();
    }

    boolean isFavoritePlaylist(Playlist playlist) {
        if (playlist == null || playlist == Playlist.EMPTY || playlist.getSourceRef().isBlank()) {
            return false;
        }
        return isFavoritePlaylistReference(playlist.getSourceRef());
    }

    boolean isFavoritePlaylistReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return false;
        }
        TuneWeavePlatform platform = TuneWeaveReference.platform(reference);
        return reference.equals(favoritePlaylistReferences.get(favoriteKey(platform)));
    }

    boolean supportsFavoriteIntelligence(Playlist playlist) {
        return isFavoritePlaylist(playlist)
                && TuneWeaveReference.platform(playlist.getSourceRef()) == TuneWeavePlatform.NETEASE;
    }

    List<MusicDetail> loadFavoriteIntelligence(Playlist playlist, String startReference) {
        if (!supportsFavoriteIntelligence(playlist)) {
            throw new IllegalArgumentException(
                    "Favorite intelligence is only available for NetEase favorites");
        }
        if (startReference != null && !startReference.isBlank() && !startReference.startsWith("netease:"))
            throw new IllegalArgumentException("Invalid favorite intelligence starting track");
        TuneWeavePlatform platform = TuneWeavePlatform.NETEASE;
        var capabilities = gateway.requestForPlatform(platform, "GET", "/v1/capabilities", Map.of("platform", platform.apiName()), null).data();
        if (!TuneWeaveCapabilities.supports(capabilities, platform.apiName(), "favorite_intelligence")) {
            throw new IllegalStateException("TuneWeave does not support favorite intelligence");
        }
        List<JsonElement> favoriteTracks = elements(gateway.requestForPlatform(
                platform, "GET", "/v1/account/favorites/tracks",
                Map.of("platform", platform.apiName(), "limit", "1", "offset", "0"), null).data());
        if (favoriteTracks.isEmpty()) {
            return List.of();
        }
        MusicDetail seed = entities.toTrack(platform, unwrap(favoriteTracks.getFirst()));
        if (seed == MusicDetail.NONE || seed.getSourceRef().isBlank()) {
            return List.of();
        }
        if (!seed.getSourceRef().startsWith("netease:")) throw new IllegalArgumentException("Invalid favorite intelligence seed");

        Map<String, String> query = new LinkedHashMap<>();
        query.put("platform", platform.apiName());
        query.put("seed", seed.getSourceRef());
        query.put("count", "1");
        if (startReference != null && startReference.startsWith(platform.apiName() + ':')) {
            query.put("start", startReference);
        }
        JsonObject queue = object(gateway.requestForPlatform(
                platform, "GET", "/v1/account/favorites/tracks/intelligence", query, null).data());
        LinkedHashMap<String, MusicDetail> result = new LinkedHashMap<>();
        for (JsonElement value : elements(queue.get("items"))) {
            if (result.size() >= FAVORITE_INTELLIGENCE_MAX_RECOMMENDATIONS) break;
            JsonObject item = unwrap(value);
            JsonObject trackData = object(item.get("track"));
            MusicDetail track = entities.toTrack(platform, trackData.isEmpty() ? item : trackData);
            if (track != MusicDetail.NONE && !track.getSourceRef().isBlank()) {
                if (!track.getSourceRef().startsWith("netease:") || !"track".equals(track.getSourceKind())
                        || track.getDurationMillis() <= 0) throw new IllegalArgumentException("Invalid favorite intelligence track");
                result.putIfAbsent(track.getSourceRef(), track);
            }
        }
        return List.copyOf(result.values());
    }

    private UserCategoryPlaylists loadBilibiliFavoriteFolders(boolean refresh, java.util.function.Consumer<UserCategoryPlaylists> progress) {
        TuneWeaveSession session = authentication.cachedSession(TuneWeavePlatform.BILIBILI);
        if (session == null) {
            session = authentication.loadSession(TuneWeavePlatform.BILIBILI);
        }
        if (session == null || session.userId() == null || session.userId().isBlank()) {
            throw new TuneWeaveApiClient.TuneWeaveException(
                    "Bilibili session did not provide a user id", false);
        }
        String reference = "bilibili:" + session.userId();
        TuneWeaveSession captured = session;
        List<JsonElement> data = loadAccountPages(TuneWeavePlatform.BILIBILI,
                "/v1/users/" + TuneWeaveApiClient.encodePathSegment(reference) + "/playlists/created", refresh,
                values -> progress.accept(mapBilibiliFolders(captured, values)));
        return mapBilibiliFolders(captured, data);
    }

    private UserCategoryPlaylists mapBilibiliFolders(TuneWeaveSession session, List<JsonElement> data) {
        ObservableSequencedSet<Playlist> created = new ObservableSequencedSet<>();
        ObservableSequencedSet<Playlist> subscribed = new ObservableSequencedSet<>();
        Playlist defaultFolder = null;
        for (JsonElement item : data) {
            JsonObject raw = unwrap(item);
            String playlistReference = string(raw, "ref", "");
            if (!playlistReference.startsWith("bilibili:favorite:")) {
                continue;
            }
            Playlist playlist = entities.toPlaylist(TuneWeavePlatform.BILIBILI, raw);
            JsonObject extensions = raw.has("extensions") && raw.get("extensions").isJsonObject()
                    ? raw.getAsJsonObject("extensions") : new JsonObject();
            if (bool(extensions, "default", false) && defaultFolder == null) {
                defaultFolder = playlist;
            } else {
                created.add(playlist);
            }
        }
        if (defaultFolder == null && !created.isEmpty()) {
            defaultFolder = created.removeFirst();
        }
        if (defaultFolder == null) {
            defaultFolder = Playlist.fromTuneWeave(
                    entities.stableId(TuneWeavePlatform.BILIBILI, "playlist:account:favorites"),
                    "account:favorites:bilibili", "Bilibili Favorites", MusicHud.ICON_BASE64,
                    0, 0, session.toMusicHudProfile());
            entities.cachePlaylist(defaultFolder);
        }
        favoritePlaylistReferences.put(favoriteKey(TuneWeavePlatform.BILIBILI), defaultFolder.getSourceRef());
        return new UserCategoryPlaylists(defaultFolder, created, subscribed);
    }
}
