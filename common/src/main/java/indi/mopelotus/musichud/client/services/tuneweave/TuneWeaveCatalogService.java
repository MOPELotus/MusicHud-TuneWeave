package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.Lyric;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import indi.mopelotus.musichud.client.services.music.AccountScope;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.bool;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.elements;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.integer;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.longValue;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.object;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.requiredString;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.string;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.unwrap;

/** Owns collection details, track metadata, video metadata, subtitles, and search. */
final class TuneWeaveCatalogService {
    private static final int PAGE_SIZE = 100;

    private final TuneWeaveGateway gateway;
    private final TuneWeaveEntityMapper entities;
    private final TuneWeaveAccountService account;
    private record PageKey(AccountScope account, String path) {}
    private final ResumableOffsetCollection<PageKey, MusicDetail> pages = new ResumableOffsetCollection<>();

    private final PagedCollectionLoader videoPages;
    void clear() { pages.clear(); videoPages.clear(); }

    Album loadKnownAlbumForImport(String reference) {
        TuneWeavePlatform platform = platform(reference);
        Album metadata = entities.album(entities.accountScope(platform), reference);
        if (metadata == null) return null;
        String path = "/v1/albums/" + TuneWeaveApiClient.encodePathSegment(reference) + "/tracks";
        var checkpoint = pages.peekFresh(new PageKey(entities.accountScope(platform), path));
        if (checkpoint == null || !checkpoint.complete() && checkpoint.nextOffset() == 0 && checkpoint.items().isEmpty()) return null;
        if (!checkpoint.complete()) return loadAlbumDetail(reference, false, ignored -> {});
        Album copy = metadata.copyWithPusherInfo(metadata.getPusherInfo());
        var tracks = new ObservableSequencedSet<MusicDetail>(); tracks.addAll(checkpoint.items());
        copy.setMusicDetails(tracks);
        return copy;
    }

    /** Null means there is no fresh page checkpoint; the caller may materialize a descriptor. */
    Playlist loadKnownPlaylistForImport(String reference) {
        if (TuneWeaveReference.isBilibiliFavoritesPlaceholder(reference)) return loadPlaylistDetail(reference);
        TuneWeavePlatform platform = platform(reference);
        Playlist metadata = entities.playlist(entities.accountScope(platform), reference);
        if (metadata == null) return null;
        boolean favorite = platform != TuneWeavePlatform.BILIBILI && account.isFavoritePlaylistReference(reference);
        String path = favorite ? "/v1/account/favorites/tracks"
                : "/v1/playlists/" + TuneWeaveApiClient.encodePathSegment(reference) + "/items";
        var checkpoint = pages.peekFresh(new PageKey(entities.accountScope(platform), path));
        if (checkpoint == null) return null;
        if (!checkpoint.complete() && checkpoint.nextOffset() == 0 && checkpoint.items().isEmpty()) return null;
        if (checkpoint.complete()) return playlistSnapshot(metadata, checkpoint);
        return loadPlaylistDetail(reference, false, ignored -> {});
    }

    TuneWeaveCatalogService(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities,
                            TuneWeaveAccountService account) {
        this.gateway = Objects.requireNonNull(gateway);
        this.entities = Objects.requireNonNull(entities);
        this.account = Objects.requireNonNull(account);
        videoPages = new PagedCollectionLoader(gateway, entities);
    }

    Playlist loadPlaylistDetail(long id) {
        return loadPlaylistDetail(id, false, ignored -> {});
    }

    Playlist loadPlaylistDetail(long id, boolean refresh, Consumer<Playlist> progress) {
        Playlist cached = entities.playlist(id);
        if (cached == null || cached.getSourceRef().isBlank()) {
            return Playlist.EMPTY;
        }
        return loadPlaylistDetail(cached.getSourceRef(), refresh, progress);
    }

    Playlist loadPlaylistDetail(String reference) {
        return loadPlaylistDetail(reference, false, ignored -> {});
    }

    Playlist loadPlaylistDetail(String reference, boolean refresh, Consumer<Playlist> progress) {
        if (reference == null || reference.isBlank()) {
            return Playlist.EMPTY;
        }
        TuneWeavePlatform platform = platform(reference);
        if (TuneWeaveReference.isBilibiliFavoritesPlaceholder(reference)) {
            Playlist current = account.loadPlaylists(TuneWeavePlatform.BILIBILI, refresh, ignored -> {}).getLikeList();
            if (!TuneWeaveReference.isBilibiliFavoritesPlaceholder(current.getSourceRef()))
                return loadPlaylistDetail(current.getSourceRef(), refresh, progress);
            progress.accept(current);
            return current;
        }
        if (platform != TuneWeavePlatform.BILIBILI
                && account.isFavoritePlaylistReference(reference)) {
            return loadFavoritePlaylist(platform, reference, refresh, progress);
        }

        Playlist playlist = entities.toPlaylist(platform, unwrap(gateway.requestForPlatform(
                platform, "GET", "/v1/playlists/"
                        + TuneWeaveApiClient.encodePathSegment(reference), Map.of(), null).data()));
        var snapshot = loadTracks(platform, "/v1/playlists/" + TuneWeaveApiClient.encodePathSegment(reference) + "/items",
                Map.of(), refresh, page -> progress.accept(playlistSnapshot(playlist, page)));
        setPlaylistTracks(playlist, snapshot.items());
        entities.cachePlaylist(playlist);
        return playlist;
    }

    Album loadAlbumDetail(long id) {
        return loadAlbumDetail(id, false, ignored -> {});
    }

    Album loadAlbumDetail(long id, boolean refresh, Consumer<Album> progress) {
        Album cached = entities.album(id);
        return cached == null ? Album.NONE : loadAlbumDetail(cached.getSourceRef(), refresh, progress);
    }

    Album loadAlbumDetail(String reference) {
        return loadAlbumDetail(reference, false, ignored -> {});
    }

    Album loadAlbumDetail(String reference, boolean refresh, Consumer<Album> progress) {
        if (reference == null || reference.isBlank()) {
            return Album.NONE;
        }
        TuneWeavePlatform platform = platform(reference);
        Album album = entities.toAlbum(platform, unwrap(gateway.requestForPlatform(
                platform, "GET", "/v1/albums/"
                        + TuneWeaveApiClient.encodePathSegment(reference), Map.of(), null).data()));
        var snapshot = loadTracks(platform, "/v1/albums/" + TuneWeaveApiClient.encodePathSegment(reference) + "/tracks",
                Map.of(), refresh, page -> {
                    Album partial = album.copyWithPusherInfo(album.getPusherInfo());
                    ObservableSequencedSet<MusicDetail> values = new ObservableSequencedSet<>();
                    values.addAll(page.items());
                    partial.setMusicDetails(values);
                    progress.accept(partial);
                });
        ObservableSequencedSet<MusicDetail> albumTracks = new ObservableSequencedSet<>();
        albumTracks.addAll(snapshot.items());
        album.setMusicDetails(albumTracks);
        return album;
    }

    Artist loadArtistDetail(long id) {
        Artist cached = entities.artist(id);
        return cached == null ? new Artist() : loadArtistDetail(cached.getSourceRef());
    }

    Artist loadArtistDetail(String reference) {
        if (reference == null || reference.isBlank()) {
            return new Artist();
        }
        TuneWeavePlatform platform = platform(reference);
        return entities.toArtist(platform, unwrap(gateway.requestForPlatform(
                platform, "GET", "/v1/artists/"
                        + TuneWeaveApiClient.encodePathSegment(reference), Map.of(), null).data()));
    }

    List<MusicDetail> loadArtistTracks(long id, int offset) {
        Artist cached = entities.artist(id);
        return cached == null ? List.of() : loadArtistTracks(cached.getSourceRef(), offset);
    }

    List<MusicDetail> loadArtistTracks(String reference, int offset) {
        if (reference == null || reference.isBlank()) {
            return List.of();
        }
        TuneWeavePlatform platform = platform(reference);
        List<MusicDetail> result = new ArrayList<>();
        for (JsonElement item : elements(gateway.requestForPlatform(
                platform, "GET", "/v1/artists/"
                        + TuneWeaveApiClient.encodePathSegment(reference) + "/tracks",
                Map.of("limit", "50", "offset", Integer.toString(Math.max(0, offset))), null).data())) {
            MusicDetail track = entities.toTrack(platform, unwrap(item));
            if (track != MusicDetail.NONE) {
                result.add(track);
            }
        }
        return result;
    }

    MusicDetail loadTrackDetail(MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail.getSourceRef().isBlank()
                || "video".equals(musicDetail.getSourceKind())) {
            return musicDetail == null ? MusicDetail.NONE : musicDetail;
        }
        TuneWeavePlatform platform = platform(musicDetail.getSourceRef());
        MusicDetail detail = entities.toTrack(platform, unwrap(gateway.requestForPlatform(
                platform, "GET", "/v1/tracks/"
                        + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()),
                Map.of(), null).data()));
        entities.cacheTrack(detail);
        return detail;
    }

    TuneWeaveVideo loadVideoDetail(MusicDetail musicDetail) {
        TuneWeaveReference.require(musicDetail == null ? null : musicDetail.getSourceRef(), "video");
        TuneWeavePlatform platform = platform(musicDetail.getSourceRef());
        JsonObject detail = object(gateway.requestForPlatform(
                platform, "GET", "/v1/videos/"
                        + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()),
                Map.of("type", "video"), null).data());
        JsonObject video = detail.has("video") && detail.get("video").isJsonObject()
                ? detail.getAsJsonObject("video") : detail;
        return entities.toVideoInfo(video);
    }

    MusicDetail loadVideoPlaybackDetail(MusicDetail requested) {
        TuneWeaveVideo video = loadVideoDetail(requested);
        if (!requested.getSourcePartRef().isBlank()) {
            TuneWeaveVideoPart part = loadVideoParts(video.reference()).stream()
                    .filter(candidate -> requested.getSourcePartRef().equals(candidate.reference()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "TuneWeave video part is no longer available"));
            return videoPartTrack(video, part);
        }
        TuneWeavePlatform platform = platform(video.reference());
        List<Artist> creators = video.creators().stream()
                .map(creator -> entities.videoCreatorArtist(platform, creator)).toList();
        Album album = entities.videoAlbum(platform, video.reference(), video.title(),
                video.coverUrl(), creators);
        MusicDetail result = MusicDetail.fromTuneWeave(
                entities.stableId(platform, "video:" + video.reference()),
                video.reference(), "video", video.title(), video.durationMillis(), album, creators);
        result.setPusherInfo(PusherInfo.EMPTY);
        entities.cacheTrack(result);
        return result;
    }

    List<TuneWeaveVideoPart> loadVideoParts(String reference) { return loadVideoParts(reference, false, ignored -> {}); }

    List<TuneWeaveVideoPart> loadVideoParts(String reference, boolean refresh, Consumer<List<TuneWeaveVideoPart>> progress) {
        TuneWeaveReference.require(reference, "video");
        TuneWeavePlatform platform = platform(reference);
        return videoPages.load(platform, "/v1/videos/" + TuneWeaveApiClient.encodePathSegment(reference) + "/parts",
                Map.of("type", "video"), refresh, false, item -> {
                    JsonObject value = unwrap(item);
                    return new TuneWeaveVideoPart(requiredString(value, "ref"), integer(value, "page", 1),
                            string(value, "title", reference), integer(value, "duration_ms", 0),
                            integer(value, "width", 0), integer(value, "height", 0));
                }, (parts, metadata) -> progress.accept(parts));
    }

    MusicDetail videoPartTrack(TuneWeaveVideo video, TuneWeaveVideoPart part) {
        TuneWeavePlatform platform = platform(video.reference());
        List<Artist> creators = video.creators().stream()
                .map(creator -> entities.videoCreatorArtist(platform, creator)).toList();
        Album album = entities.videoAlbum(
                platform, video.reference(), video.title(), video.coverUrl(), creators);
        MusicDetail result = MusicDetail.fromTuneWeave(
                entities.stableId(platform,
                        "video-part:" + video.reference() + ':' + part.reference()),
                video.reference(), "video", part.title(), part.durationMillis(), album, creators);
        result.setSourcePartRef(part.reference());
        result.setPusherInfo(PusherInfo.EMPTY);
        entities.cacheTrack(result);
        return result;
    }

    LyricInfo loadLyrics(MusicDetail musicDetail) {
        if (musicDetail == null || musicDetail.getSourceRef().isBlank()
                || "video".equals(musicDetail.getSourceKind())
                || "radio_station".equals(musicDetail.getSourceKind())) {
            return LyricInfo.NONE;
        }
        TuneWeavePlatform platform = platform(musicDetail.getSourceRef());
        JsonObject lyrics;
        if ("podcast_episode".equals(musicDetail.getSourceKind())) {
            JsonObject data = object(gateway.requestForPlatform(
                    platform, "GET", "/v1/episodes/"
                            + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()) + "/lyrics",
                    Map.of(), null).data());
            lyrics = object(data.get("lyrics"));
        } else {
            lyrics = unwrap(gateway.requestForPlatform(
                    platform, "GET", "/v1/tracks/"
                            + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()) + "/lyrics",
                    Map.of("word_synced", "true", "translated", "true", "romanized", "true"),
                    null).data());
        }
        JsonObject extensions = object(lyrics.get("extensions"));
        String wordSyncedTranslated = string(extensions, "word_synced_translated",
                string(lyrics, "translated", ""));
        return new LyricInfo(new Lyric(string(lyrics, "plain", "")),
                new Lyric(string(lyrics, "translated", "")),
                new Lyric(string(lyrics, "word_synced", "")),
                new Lyric(wordSyncedTranslated));
    }

    LyricInfo loadVideoLyrics(MusicDetail musicDetail) {
        TuneWeaveReference.require(musicDetail == null ? null : musicDetail.getSourceRef(), "video");
        TuneWeavePlatform platform = platform(musicDetail.getSourceRef());
        String partReference = musicDetail.getSourcePartRef();
        if (partReference == null || partReference.isBlank()) {
            List<JsonElement> parts = elements(gateway.requestForPlatform(
                    platform, "GET", "/v1/videos/"
                            + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()) + "/parts",
                    Map.of("type", "video", "limit", "1", "offset", "0"), null).data());
            if (parts.isEmpty()) {
                return LyricInfo.NONE;
            }
            partReference = string(unwrap(parts.getFirst()), "ref", "");
            if (partReference.isBlank()) {
                return LyricInfo.NONE;
            }
        }

        String videoPath = "/v1/videos/"
                + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef());
        Map<String, String> query = Map.of("type", "video", "part", partReference);
        JsonObject catalog = object(gateway.requestForPlatform(
                platform, "GET", videoPath + "/subtitles", query, null).data());
        JsonObject selected = selectSubtitle(catalog);
        if (selected == null) {
            return LyricInfo.NONE;
        }

        JsonObject document = object(gateway.requestForPlatform(
                platform, "GET", videoPath + "/subtitles/"
                        + TuneWeaveApiClient.encodePathSegment(string(selected, "ref", "")),
                query, null).data());
        StringBuilder lrc = new StringBuilder();
        for (JsonElement element : elements(document.get("cues"))) {
            JsonObject cue = unwrap(element);
            String text = string(cue, "text", "").replace('\r', ' ').replace('\n', ' ').trim();
            if (text.isBlank()) {
                continue;
            }
            long startMillis = Math.max(0L, longValue(cue, "start_ms", 0L));
            long minutes = startMillis / 60_000L;
            long seconds = (startMillis / 1_000L) % 60L;
            long millis = startMillis % 1_000L;
            lrc.append(String.format(Locale.ROOT,
                    "[%02d:%02d.%03d]%s%n", minutes, seconds, millis, text));
        }
        return lrc.isEmpty()
                ? LyricInfo.NONE
                : new LyricInfo(new Lyric(lrc.toString()), Lyric.NONE, Lyric.NONE, Lyric.NONE);
    }

    List<?> search(String keywords, SearchType type, int offset, TuneWeavePlatform platform) {
        return searchPage(keywords, type, offset, platform).items();
    }

    TuneWeaveSearchPage searchPage(String keywords, SearchType searchType, int offset, TuneWeavePlatform platform) {
        if (keywords == null || keywords.isBlank() || keywords.length() > 1000 || offset < 0 || offset > OffsetPagination.MAX_ITEMS)
            throw new IllegalArgumentException("Invalid search bounds");
        String type = switch (searchType) {
            case ALBUM -> "album";
            case ARTIST -> "artist";
            case PLAYLIST -> "playlist";
            case RADIO -> "podcast";
            default -> platform == TuneWeavePlatform.BILIBILI ? "video" : "track";
        };
        Map<String, String> query = new LinkedHashMap<>();
        query.put("q", keywords);
        query.put("type", type);
        query.put("platform", platform.apiName());
        query.put("limit", "50");
        query.put("offset", Integer.toString(Math.max(0, offset)));
        if (platform == TuneWeavePlatform.BILIBILI && "video".equals(type)) {
            query.put("order", "relevance");
        }

        List<JsonObject> rawItems = new ArrayList<>();
        var page = OffsetPagination.readPage(gateway.requestForPlatform(platform, "GET", "/v1/search", query, null), offset);
        for (JsonElement item : page.items()) {
            JsonObject value = unwrap(item);
            if (!value.isEmpty()) {
                rawItems.add(value);
            }
        }
        List<?> mapped = switch (searchType) {
            case ALBUM -> rawItems.stream().map(value -> entities.toAlbum(platform, value)).toList();
            case ARTIST -> rawItems.stream().map(value -> entities.toArtist(platform, value)).toList();
            case PLAYLIST -> rawItems.stream().map(value -> entities.toPlaylist(platform, value)).toList();
            case RADIO -> rawItems.stream().map(value -> entities.toPodcast(platform, value)).toList();
            default -> rawItems.stream().map(value -> platform == TuneWeavePlatform.BILIBILI
                    ? entities.toVideoTrack(platform, value) : entities.toTrack(platform, value)).toList();
        };
        return new TuneWeaveSearchPage(mapped, page.nextOffset(), !page.complete());
    }

    private Playlist loadFavoritePlaylist(TuneWeavePlatform platform, String reference, boolean refresh, Consumer<Playlist> progress) {
        Playlist playlist = entities.playlist(entities.accountScope(platform), reference);
        if (playlist == null) return Playlist.EMPTY;
        var snapshot = loadTracks(platform, "/v1/account/favorites/tracks",
                Map.of("platform", platform.apiName()), refresh,
                page -> progress.accept(playlistSnapshot(playlist, page)));
        setPlaylistTracks(playlist, snapshot.items());
        entities.cachePlaylist(playlist);
        return playlist;
    }

    private static Playlist playlistSnapshot(Playlist metadata, ResumableOffsetCollection.Snapshot<MusicDetail> page) {
        Playlist snapshot = metadata.copyWithPusherInfo(metadata.getPusherInfo());
        setPlaylistTracks(snapshot, page.items());
        if (!page.complete()) snapshot.setMusicTrackCount(Math.max(metadata.getMusicTrackCount(), page.items().size()));
        return snapshot;
    }

    private ResumableOffsetCollection.Snapshot<MusicDetail> loadTracks(TuneWeavePlatform platform, String path,
            Map<String, String> baseQuery, boolean refresh, Consumer<ResumableOffsetCollection.Snapshot<MusicDetail>> progress) {
        return pages.load(new PageKey(entities.accountScope(platform), path), refresh, offset -> {
            Map<String, String> query = new LinkedHashMap<>(baseQuery);
            query.put("limit", Integer.toString(PAGE_SIZE));
            query.put("offset", Integer.toString(offset));
            return gateway.requestForPlatform(platform, "GET", path, query, null);
        }, item -> {
            JsonObject raw = unwrap(item);
            MusicDetail track = "video".equals(string(raw, "kind", "track"))
                    ? entities.toVideoTrack(platform, raw) : entities.toTrack(platform, raw);
            return track == MusicDetail.NONE ? null : track;
        }, MusicDetail::getSourceRef, progress);
    }

    private List<JsonElement> loadAllOffsetPages(TuneWeavePlatform platform, String path,
                                                  Map<String, String> baseQuery) {
        return OffsetPagination.loadAll(offset -> {
            Map<String, String> query = new LinkedHashMap<>(baseQuery);
            query.put("limit", Integer.toString(PAGE_SIZE));
            query.put("offset", Integer.toString(offset));
            return gateway.requestForPlatform(platform, "GET", path, query, null);
        });
    }

    private JsonObject selectSubtitle(JsonObject catalog) {
        String defaultLanguage = string(catalog, "default_language", "");
        JsonObject selected = null;
        for (JsonElement element : elements(catalog.get("items"))) {
            JsonObject candidate = unwrap(element);
            if (string(candidate, "ref", "").isBlank()) {
                continue;
            }
            if (selected == null || (!defaultLanguage.isBlank()
                    && defaultLanguage.equals(string(candidate, "language", "")))) {
                selected = candidate;
            }
            if (!defaultLanguage.isBlank()
                    && defaultLanguage.equals(string(candidate, "language", ""))) {
                break;
            }
        }
        return selected;
    }

    private TuneWeavePlatform platform(String reference) {
        return TuneWeaveReference.platformOrDefault(reference, gateway.defaultPlatform());
    }

    private static void setPlaylistTracks(Playlist playlist, List<MusicDetail> tracks) {
        ObservableSequencedSet<MusicDetail> playlistTracks = new ObservableSequencedSet<>();
        playlistTracks.addAll(tracks);
        playlist.setTracks(playlistTracks);
        playlist.setMusicTrackCount(tracks.size());
    }
}
