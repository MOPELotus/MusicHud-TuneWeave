package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.api.SearchType;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.Quality;
import indi.mopelotus.musichud.beans.music.UserCategoryPlaylists;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Stable client facade over TuneWeave's authentication, catalog, library, and playback domains. */
public final class TuneWeaveClientService {
    private static final TuneWeaveClientService INSTANCE = new TuneWeaveClientService();
    private final TuneWeaveGateway gateway = new TuneWeaveGateway();
    private final Map<TuneWeavePlatform, TuneWeaveSession> sessionProfiles = new ConcurrentHashMap<>();
    private final TuneWeaveAuthenticationService authentication =
            new TuneWeaveAuthenticationService(gateway, sessionProfiles);
    private final TuneWeaveEntityMapper entities = new TuneWeaveEntityMapper(sessionProfiles::get);
    private final TuneWeaveAccountRequests accountRequests = new TuneWeaveAccountRequests(gateway, entities, authentication);
    private final TuneWeaveAccountService account =
            new TuneWeaveAccountService(gateway, authentication, entities);
    private final TuneWeaveCloudService cloud =
            new TuneWeaveCloudService(gateway, authentication, entities);
    private final TuneWeaveProgramService programs = new TuneWeaveProgramService(gateway, entities);
    private final TuneWeaveCatalogService catalog =
            new TuneWeaveCatalogService(gateway, entities, account);
    private final TuneWeavePlaylistService platformPlaylists =
            new TuneWeavePlaylistService(gateway, entities, catalog);
    private final LocalUniPlaylistStore localPlaylists = new LocalUniPlaylistStore();
    private final TuneWeaveUniPlaylistService uniPlaylists =
            new TuneWeaveUniPlaylistService(gateway, entities, localPlaylists, catalog::loadKnownPlaylistForImport, catalog::loadKnownAlbumForImport);
    private final TuneWeavePlaybackService playback = new TuneWeavePlaybackService(gateway);

    private TuneWeaveClientService() {
    }

    /** Capture before submitting asynchronous work so it cannot adopt a later account. */
    public <T> Supplier<T> prepareRequest(Supplier<T> operation) {
        return gateway.capture(entities.capture(operation));
    }

    /** UI jobs pin credentials/epoch before queuing; each facade operation owns its mapping scope.
     * This permits account requests to initialize a cold profile before capturing that scope. */
    public <T> Supplier<T> prepareViewRequest(Supplier<T> operation) {
        Object epoch = entities.captureEpoch();
        return gateway.capture(() -> {
            entities.requireEpoch(epoch);
            T result = operation.get();
            entities.requireEpoch(epoch);
            return result;
        });
    }

    public <T> Supplier<T> prepareAccountRequest(TuneWeavePlatform platform, Supplier<T> operation) {
        return accountRequests.prepare(platform, operation);
    }

    public <T, R> java.util.function.Function<T, R> prepareFunction(java.util.function.Function<T, R> operation) {
        Object epoch = entities.captureEpoch();
        return gateway.captureFunction(value -> {
            entities.requireEpoch(epoch);
            return entities.capture(() -> operation.apply(value)).get();
        });
    }

    public <T, U> java.util.function.BiConsumer<T, U> prepareBiConsumer(java.util.function.BiConsumer<T, U> operation) {
        record Arguments<A, B>(A first, B second) {}
        var prepared = this.<Arguments<T, U>, Void>prepareFunction(args -> {
            operation.accept(args.first(), args.second()); return null;
        });
        return (first, second) -> prepared.apply(new Arguments<>(first, second));
    }

    public TuneWeavePlatform platformOfEntity(Class<?> type, long id) {
        return scoped(() -> {
            String reference;
            if (type == Album.class) { var value = entities.album(id); reference = value == null ? "" : value.getSourceRef(); }
            else if (type == Artist.class) { var value = entities.artist(id); reference = value == null ? "" : value.getSourceRef(); }
            else { var value = entities.playlist(id); reference = value == null ? "" : value.getSourceRef(); }
            if (reference.isBlank()) throw new IllegalArgumentException("Unknown entity platform");
            return TuneWeaveReference.platformOrDefault(reference, gateway.defaultPlatform());
        });
    }

    public Supplier<Playlist> prepareFavoritePlaylist(MusicDetail track) {
        TuneWeavePlatform platform = TuneWeaveReference.platformOrDefault(track.getSourceRef(), gateway.defaultPlatform());
        return accountRequests.prepare(platform, () -> {
            var favorites = account.loadPlaylists(platform).getLikeList();
            return catalog.loadPlaylistDetail(favorites.getSourceRef());
        });
    }

    private <T> T scoped(Supplier<T> operation) { return prepareRequest(operation).get(); }

    private void scopedRun(Runnable operation) {
        scoped(() -> { operation.run(); return null; });
    }

    public void invalidateEntityCaches() {
        entities.clear();
        account.clear();
        catalog.clear();
        cloud.clear();
        programs.clear();
    }

    public static TuneWeaveClientService getInstance() {
        return INSTANCE;
    }

    public TuneWeavePlatform defaultPlatform() {
        return gateway.defaultPlatform();
    }

    public void setDefaultPlatform(TuneWeavePlatform platform) {
        gateway.setDefaultPlatform(platform);
    }

    public boolean isAvailable() {
        return gateway.isAvailable();
    }

    public boolean hasCredential(TuneWeavePlatform platform) {
        return gateway.hasCredential(platform);
    }

    public TuneWeaveSession cachedSession(TuneWeavePlatform platform) {
        return authentication.cachedSession(platform);
    }

    public boolean hasPlaylist(long id) {
        return entities.hasPlaylist(id);
    }

    public boolean hasAlbum(long id) {
        return entities.hasAlbum(id);
    }

    public boolean hasArtist(long id) {
        return entities.hasArtist(id);
    }

    public TuneWeaveQrSession startQrLogin(TuneWeavePlatform platform, String loginType) {
        return authentication.startQrLogin(platform, loginType);
    }

    public TuneWeaveLoginAttempt beginLogin(TuneWeavePlatform platform) { return authentication.beginLogin(platform); }
    public void cancelLogin(TuneWeaveLoginAttempt attempt) { authentication.cancelLogin(attempt); }
    public void cancelLogins() { authentication.cancelLogins(); }
    public boolean isLoginCurrent(TuneWeaveLoginAttempt attempt) { return authentication.isCurrent(attempt); }
    public void publishLogin(TuneWeaveLoginAttempt attempt, Runnable publish) { authentication.publishLogin(attempt, publish); }
    public TuneWeaveQrSession startQrLogin(TuneWeaveLoginAttempt attempt, String type) { return authentication.startQrLogin(attempt, type); }
    public TuneWeaveChallengeSession startSmsLogin(TuneWeaveLoginAttempt attempt, String phone, String region) {
        return authentication.startSmsLogin(attempt, phone, region);
    }

    public TuneWeaveQrPoll pollQrLogin(TuneWeaveQrSession session) {
        return authentication.pollQrLogin(session);
    }

    public TuneWeaveSession loginWithPassword(TuneWeavePlatform platform, String principalType,
                                              String principal, String password, String passwordFormat,
                                              String countryCode) {
        return authentication.loginWithPassword(
                platform, principalType, principal, password, passwordFormat, countryCode);
    }

    public TuneWeaveChallengeSession startSmsLogin(TuneWeavePlatform platform, String principal,
                                                   String countryCode) {
        return authentication.startSmsLogin(platform, principal, countryCode);
    }

    public TuneWeaveSession verifySmsLogin(TuneWeaveChallengeSession session, String code) {
        return authentication.verifySmsLogin(session, code);
    }

    public TuneWeaveSession loadSession(TuneWeavePlatform platform) {
        return authentication.loadSession(platform);
    }

    public TuneWeaveSession refreshSession(TuneWeavePlatform platform) {
        return authentication.refreshSession(platform);
    }

    /** Loads the caller's account playlists without routing the private credential through Minecraft. */
    public UserCategoryPlaylists loadAccountPlaylists() {
        return loadAccountPlaylists(gateway.defaultPlatform());
    }

    public UserCategoryPlaylists loadAccountPlaylists(TuneWeavePlatform platform, boolean refresh, java.util.function.Consumer<UserCategoryPlaylists> progress) {
        return accountRequests.prepare(platform, () -> account.loadPlaylists(platform, refresh, progress)).get();
    }
    public LinkedHashSet<Album> loadAccountAlbums(TuneWeavePlatform platform, boolean refresh, java.util.function.Consumer<LinkedHashSet<Album>> progress) {
        return accountRequests.prepare(platform, () -> account.loadAlbums(platform, refresh, progress)).get();
    }
    public LinkedHashSet<Artist> loadAccountArtists(TuneWeavePlatform platform, boolean refresh, java.util.function.Consumer<LinkedHashSet<Artist>> progress) {
        return accountRequests.prepare(platform, () -> account.loadArtists(platform, refresh, progress)).get();
    }

    public UserCategoryPlaylists loadAccountPlaylists(TuneWeavePlatform platform) {
        return accountRequests.prepare(platform, () -> account.loadPlaylists(platform)).get();
    }

    public LinkedHashSet<Album> loadAccountAlbums() {
        return loadAccountAlbums(gateway.defaultPlatform());
    }

    public LinkedHashSet<Album> loadAccountAlbums(TuneWeavePlatform platform) {
        return accountRequests.prepare(platform, () -> account.loadAlbums(platform)).get();
    }

    public LinkedHashSet<Artist> loadAccountArtists() {
        return loadAccountArtists(gateway.defaultPlatform());
    }

    public LinkedHashSet<Artist> loadAccountArtists(TuneWeavePlatform platform) {
        return accountRequests.prepare(platform, () -> account.loadArtists(platform)).get();
    }

    public TuneWeaveCloudLibrary loadCloudLibrary() {
        return scoped(() -> cloud.loadLibrary());
    }

    public TuneWeaveCloudLibrary loadCloudLibrary(boolean refresh, java.util.function.Consumer<TuneWeaveCloudLibrary> progress) {
        return scoped(() -> cloud.loadLibrary(refresh, progress));
    }

    public void deleteCloudTrack(TuneWeaveCloudTrack cloudTrack) {
        scopedRun(() -> cloud.deleteTrack(cloudTrack));
        cloud.clear();
    }

    public String uploadCloudTrack(Path file, String songName, String artist, String album) {
        String result = scoped(() -> cloud.uploadTrack(file, songName, artist, album));
        cloud.clear(); return result;
    }

    public String importCloudTrack(String md5, String sourceTrackId, long bitrate, long fileSize,
                                   String fileType, String songName, String artist, String album) {
        String result = scoped(() -> cloud.importTrack(md5, sourceTrackId, bitrate, fileSize,
                fileType, songName, artist, album));
        cloud.clear(); return result;
    }

    public boolean matchCloudTrack(TuneWeaveCloudTrack cloudTrack, String targetTrackId) {
        boolean result = scoped(() -> cloud.matchTrack(cloudTrack, targetTrackId));
        cloud.clear(); return result;
    }

    public LyricInfo loadCloudLyrics(TuneWeaveCloudTrack cloudTrack) {
        return scoped(() -> cloud.loadLyrics(cloudTrack));
    }

    public LyricInfo loadCloudLyrics(MusicDetail musicDetail) {
        if (musicDetail == null || !musicDetail.isCloudSource()
                || musicDetail.getSourcePartRef().isBlank()) {
            return LyricInfo.NONE;
        }
        return scoped(() -> cloud.loadLyrics(musicDetail.getSourcePartRef()));
    }

    public MusicDetail loadCloudTrack(MusicDetail musicDetail) {
        if (musicDetail == null || !musicDetail.isCloudSource()
                || musicDetail.getSourcePartRef().isBlank()) {
            return MusicDetail.NONE;
        }
        return scoped(() -> cloud.loadTrack(musicDetail.getSourcePartRef()).track());
    }

    public void downloadCloudTrack(TuneWeaveCloudTrack cloudTrack, Path target) {
        scopedRun(() -> cloud.downloadTrack(cloudTrack, target));
    }

    public List<TuneWeavePodcastCategory> loadPodcastCategories(TuneWeavePlatform platform) {
        return scoped(() -> programs.loadPodcastCategories(platform));
    }

    public List<TuneWeavePodcast> loadPodcasts(TuneWeavePlatform platform, String categoryId) {
        return scoped(() -> programs.loadPodcasts(platform, categoryId));
    }
    public List<TuneWeavePodcast> loadPodcasts(TuneWeavePlatform platform, String categoryId, boolean refresh,
            java.util.function.Consumer<List<TuneWeavePodcast>> progress) {
        return scoped(() -> programs.loadPodcasts(platform, categoryId, refresh, progress));
    }

    public List<TuneWeavePodcast> loadAccountPodcasts(TuneWeavePlatform platform) {
        return scoped(() -> programs.loadAccountPodcasts(platform));
    }
    public List<TuneWeavePodcast> loadAccountPodcasts(TuneWeavePlatform platform, boolean refresh,
            java.util.function.Consumer<List<TuneWeavePodcast>> progress) {
        return scoped(() -> programs.loadAccountPodcasts(platform, refresh, progress));
    }

    public TuneWeavePodcast loadPodcastDetail(String reference) {
        return scoped(() -> programs.loadPodcastDetail(reference));
    }

    public List<TuneWeavePodcastEpisode> loadPodcastEpisodes(TuneWeavePodcast podcast) {
        return scoped(() -> programs.loadPodcastEpisodes(podcast));
    }
    public List<TuneWeavePodcastEpisode> loadPodcastEpisodes(TuneWeavePodcast podcast, boolean refresh,
            java.util.function.Consumer<List<TuneWeavePodcastEpisode>> progress) {
        return scoped(() -> programs.loadPodcastEpisodes(podcast, refresh, progress));
    }

    public TuneWeavePodcastEpisode loadPodcastEpisodeDetail(String reference) {
        return scoped(() -> programs.loadPodcastEpisodeDetail(reference));
    }

    public MusicDetail podcastEpisodeTrack(TuneWeavePodcast podcast,
                                           TuneWeavePodcastEpisode episode) {
        return scoped(() -> programs.podcastEpisodeTrack(podcast, episode));
    }

    public MusicDetail loadProgramPlaybackDetail(MusicDetail musicDetail) {
        return scoped(() -> programs.loadPlaybackDetail(musicDetail));
    }

    public void setPodcastSubscribed(TuneWeavePodcast podcast, boolean subscribed) {
        scopedRun(() -> programs.setPodcastSubscribed(podcast, subscribed));
        programs.clear();
    }

    public TuneWeaveRadioTaxonomy loadRadioTaxonomy(TuneWeavePlatform platform) {
        return scoped(() -> programs.loadRadioTaxonomy(platform));
    }

    public List<TuneWeaveRadioStation> loadRadioStations(TuneWeavePlatform platform,
                                                         String categoryId, String regionId) {
        return scoped(() -> programs.loadRadioStations(platform, categoryId, regionId));
    }

    public List<TuneWeaveRadioStation> loadStyledRadioStations(TuneWeavePlatform platform) {
        return scoped(() -> programs.loadStyledRadioStations(platform));
    }
    public List<TuneWeaveRadioStation> loadRadioStations(TuneWeavePlatform platform, String category, String region, boolean refresh,
            java.util.function.Consumer<List<TuneWeaveRadioStation>> progress) {
        return scoped(() -> programs.loadRadioStations(platform, category, region, refresh, progress));
    }

    public List<TuneWeaveRadioStation> loadAccountRadioStations(TuneWeavePlatform platform) {
        return scoped(() -> programs.loadAccountRadioStations(platform));
    }
    public List<TuneWeaveRadioStation> loadAccountRadioStations(TuneWeavePlatform platform, boolean refresh,
            java.util.function.Consumer<List<TuneWeaveRadioStation>> progress) {
        return scoped(() -> programs.loadAccountRadioStations(platform, refresh, progress));
    }

    public TuneWeaveRadioStation loadRadioStationDetail(String reference) {
        return scoped(() -> programs.loadRadioStationDetail(reference));
    }

    public List<MusicDetail> loadRadioPlaybackQueue(TuneWeaveRadioStation station) {
        return scoped(() -> programs.loadRadioPlaybackQueue(station));
    }

    public void setRadioStationSubscribed(TuneWeaveRadioStation station, boolean subscribed) {
        scopedRun(() -> programs.setRadioStationSubscribed(station, subscribed));
        programs.clear();
    }

    public Playlist loadPlaylistDetail(long id) {
        return scoped(() -> catalog.loadPlaylistDetail(id));
    }

    public Playlist loadPlaylistDetail(long id, boolean refresh, java.util.function.Consumer<Playlist> progress) {
        return scoped(() -> catalog.loadPlaylistDetail(id, refresh, progress));
    }

    public indi.mopelotus.musichud.client.services.music.AccountScope collectionAccountScope(long id, boolean album) {
        return scoped(() -> {
            String reference;
            if (album) {
                Album value = entities.album(id);
                reference = value == null ? "" : value.getSourceRef();
            } else {
                Playlist value = entities.playlist(id);
                reference = value == null ? "" : value.getSourceRef();
            }
            return entities.accountScope(reference.isBlank() ? gateway.defaultPlatform()
                    : TuneWeaveReference.platformOrDefault(reference, gateway.defaultPlatform()));
        });
    }

    public Album loadAlbumDetail(long id, boolean refresh, java.util.function.Consumer<Album> progress) {
        return scoped(() -> catalog.loadAlbumDetail(id, refresh, progress));
    }

    public Playlist loadPlaylistDetail(String reference) {
        return scoped(() -> catalog.loadPlaylistDetail(reference));
    }

    public Album loadAlbumDetail(long id) {
        return scoped(() -> catalog.loadAlbumDetail(id));
    }

    public Album loadAlbumDetail(String reference) {
        return scoped(() -> catalog.loadAlbumDetail(reference));
    }

    public Artist loadArtistDetail(long id) {
        return scoped(() -> catalog.loadArtistDetail(id));
    }

    public Artist loadArtistDetail(String reference) {
        return scoped(() -> catalog.loadArtistDetail(reference));
    }

    public List<MusicDetail> loadArtistTracks(long id, int offset) {
        return scoped(() -> catalog.loadArtistTracks(id, offset));
    }

    public List<MusicDetail> loadArtistTracks(String reference, int offset) {
        return scoped(() -> catalog.loadArtistTracks(reference, offset));
    }

    public MusicDetail loadTrackDetail(MusicDetail musicDetail) {
        return scoped(() -> catalog.loadTrackDetail(musicDetail));
    }

    public TuneWeaveVideo loadVideoDetail(MusicDetail musicDetail) {
        return scoped(() -> catalog.loadVideoDetail(musicDetail));
    }

    public MusicDetail loadVideoPlaybackDetail(MusicDetail musicDetail) {
        return scoped(() -> catalog.loadVideoPlaybackDetail(musicDetail));
    }

    public List<TuneWeaveVideoPart> loadVideoParts(String reference) {
        return scoped(() -> catalog.loadVideoParts(reference));
    }
    public List<TuneWeaveVideoPart> loadVideoParts(String reference, boolean refresh,
            java.util.function.Consumer<List<TuneWeaveVideoPart>> progress) {
        return scoped(() -> catalog.loadVideoParts(reference, refresh, progress));
    }

    public MusicDetail videoPartTrack(TuneWeaveVideo video, TuneWeaveVideoPart part) {
        return scoped(() -> catalog.videoPartTrack(video, part));
    }

    public LyricInfo loadLyrics(MusicDetail musicDetail) {
        return scoped(() -> catalog.loadLyrics(musicDetail));
    }

    public LyricInfo loadVideoLyrics(MusicDetail musicDetail) {
        return scoped(() -> catalog.loadVideoLyrics(musicDetail));
    }

    public void setTrackFavorite(MusicDetail musicDetail, boolean favorite) {
        scopedRun(() -> platformPlaylists.setTrackFavorite(musicDetail, favorite));
        catalog.clear();
    }

    public void setPlaylistSubscribed(Playlist playlist, boolean subscribed) {
        scopedRun(() -> platformPlaylists.setPlaylistSubscribed(playlist, subscribed));
    }

    public void setAlbumSubscribed(Album album, boolean subscribed) {
        scopedRun(() -> platformPlaylists.setAlbumSubscribed(album, subscribed));
    }

    public void setArtistSubscribed(Artist artist, boolean subscribed) {
        scopedRun(() -> platformPlaylists.setArtistSubscribed(artist, subscribed));
    }

    public void modifyPlaylistTracks(Playlist playlist, MusicDetail musicDetail, boolean add) {
        scopedRun(() -> platformPlaylists.modifyTracks(playlist, musicDetail, add));
        catalog.clear();
    }

    public Playlist createPlatformPlaylist(String name, boolean privatePlaylist) {
        return scoped(() -> platformPlaylists.create(name, privatePlaylist));
    }

    public void updatePlatformPlaylist(Playlist playlist, String name, String description) {
        scopedRun(() -> platformPlaylists.update(playlist, name, description));
    }

    public void deletePlatformPlaylist(Playlist playlist) {
        scopedRun(() -> platformPlaylists.delete(playlist));
        catalog.clear();
    }

    public void reorderPlatformPlaylists(List<Playlist> playlists) {
        scopedRun(() -> platformPlaylists.reorder(playlists));
    }

    public void reorderPlaylistTracks(Playlist playlist, List<MusicDetail> tracks) {
        scopedRun(() -> platformPlaylists.reorderTracks(playlist, tracks));
        catalog.clear();
    }

    public List<TuneWeaveUniPlaylist> listUniPlaylists() {
        return scoped(() -> uniPlaylists.list());
    }

    public TuneWeaveUniPlaylist createUniPlaylist(String name, String description) {
        return scoped(() -> uniPlaylists.create(name, description));
    }

    public TuneWeaveUniPlaylist updateUniPlaylist(String reference, String name, String description) {
        return scoped(() -> uniPlaylists.update(reference, name, description));
    }

    public void deleteUniPlaylist(String reference) {
        scopedRun(() -> uniPlaylists.delete(reference));
    }

    public List<TuneWeaveUniItem> listUniPlaylistItems(String reference) {
        return scoped(() -> uniPlaylists.items(reference));
    }

    public MusicDetail uniPlaylistItemTrack(TuneWeaveUniItem item) {
        return scoped(() -> uniPlaylists.itemTrack(item));
    }

    public void addUniPlaylistItems(String reference, List<String> resourceRefs) {
        scopedRun(() -> uniPlaylists.addItems(reference, resourceRefs));
    }

    public void addUniPlaylistItem(String reference, String resourceRef, String kind) {
        scopedRun(() -> uniPlaylists.addItem(reference, resourceRef, kind));
    }

    public void deleteUniPlaylistItem(String reference, String itemId) {
        scopedRun(() -> uniPlaylists.deleteItem(reference, itemId));
    }

    public void reorderUniPlaylistItems(String reference, List<String> itemIds) {
        scopedRun(() -> uniPlaylists.reorderItems(reference, itemIds));
    }

    public TuneWeaveUniPlaylist importUniPlaylist(String name, List<String> sourceRefs) {
        return scoped(() -> uniPlaylists.importPlaylists(name, sourceRefs));
    }

    public TuneWeaveUniPlaylist importUniPlaylistSources(
            String name, List<TuneWeaveUniImportSource> sources) {
        return scoped(() -> uniPlaylists.importSources(name, sources));
    }

    public TuneWeaveUniPlaylist importUniPlaylistSources(
            String name, String description, List<TuneWeaveUniImportSource> sources) {
        return scoped(() -> uniPlaylists.importSources(name, description, sources));
    }

    public TuneWeaveUniPlaylist appendUniPlaylistSources(
            String reference, List<TuneWeaveUniImportSource> sources) {
        return scoped(() -> uniPlaylists.appendSources(reference, sources));
    }

    public JsonObject exportUniPlaylist(String reference) {
        return scoped(() -> uniPlaylists.exportDocument(reference));
    }

    public TuneWeaveUniPlaylist importUniPlaylistDocument(JsonObject document) {
        return scoped(() -> uniPlaylists.importDocument(document));
    }

    public MusicResourceInfo getMusicResourceInfo(MusicDetail musicDetail, Quality quality) {
        return scoped(() -> playback.resolve(musicDetail, quality));
    }

    public boolean scrobble(MusicDetail musicDetail, long playedMs, MusicResourceInfo resource, Quality quality) {
        return scoped(() -> playback.scrobble(musicDetail, playedMs, resource, quality));
    }

    public java.util.function.BiConsumer<Long, MusicResourceInfo> prepareScrobble(MusicDetail detail) {
        return playback.prepareScrobble(detail);
    }

    public static boolean scrobbleEligible(MusicDetail detail, long played, MusicResourceInfo resource) {
        return TuneWeavePlaybackService.scrobbleEligible(detail, played, resource);
    }

    public TuneWeaveSearchPage searchPage(String keywords, SearchType type, int offset, TuneWeavePlatform platform) {
        return scoped(() -> catalog.searchPage(keywords, type, offset, platform));
    }

    public List<?> search(String keywords, SearchType searchType, int offset, TuneWeavePlatform platform) {
        return scoped(() -> catalog.search(keywords, searchType, offset, platform));
    }

    public void logout(TuneWeavePlatform platform) {
        authentication.logout(platform);
    }

    public Runnable prepareLogout(TuneWeavePlatform platform) {
        return authentication.prepareLogout(platform);
    }

    public void clearCredential(TuneWeavePlatform platform) {
        authentication.clearCredential(platform);
    }

    public boolean isFavoritePlaylist(Playlist playlist) {
        return scoped(() -> account.isFavoritePlaylist(playlist));
    }

    public boolean supportsFavoriteIntelligence(Playlist playlist) {
        return scoped(() -> account.supportsFavoriteIntelligence(playlist));
    }

    public List<MusicDetail> loadFavoriteIntelligence(Playlist playlist, String startReference) {
        return scoped(() -> account.loadFavoriteIntelligence(playlist, startReference));
    }

}
