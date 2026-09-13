package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.object;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.string;

/** Owns remote library mutations and provider playlist management. */
final class TuneWeavePlaylistService {
    private final TuneWeaveGateway gateway;
    private final TuneWeaveEntityMapper entities;
    private final TuneWeaveCatalogService catalog;

    TuneWeavePlaylistService(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities,
                             TuneWeaveCatalogService catalog) {
        this.gateway = Objects.requireNonNull(gateway);
        this.entities = Objects.requireNonNull(entities);
        this.catalog = Objects.requireNonNull(catalog);
    }

    void setTrackFavorite(MusicDetail musicDetail, boolean favorite) {
        TuneWeaveReference.require(musicDetail == null ? null : musicDetail.getSourceRef(), "track");
        TuneWeavePlatform platform = platform(musicDetail.getSourceRef());
        String libraryPath = "video".equals(musicDetail.getSourceKind())
                ? "/v1/account/library/videos/" : "/v1/account/favorites/tracks/";
        gateway.requestForPlatform(platform, favorite ? "PUT" : "DELETE",
                libraryPath + TuneWeaveApiClient.encodePathSegment(musicDetail.getSourceRef()),
                Map.of(), null);
    }

    void setPlaylistSubscribed(Playlist playlist, boolean subscribed) {
        TuneWeaveReference.require(playlist == null ? null : playlist.getSourceRef(), "playlist");
        TuneWeavePlatform platform = platform(playlist.getSourceRef());
        gateway.requestForPlatform(platform, subscribed ? "PUT" : "DELETE",
                "/v1/account/favorites/playlists/"
                        + TuneWeaveApiClient.encodePathSegment(playlist.getSourceRef()), Map.of(), null);
    }

    void setAlbumSubscribed(Album album, boolean subscribed) {
        TuneWeaveReference.require(album == null ? null : album.getSourceRef(), "album");
        TuneWeavePlatform platform = platform(album.getSourceRef());
        gateway.requestForPlatform(platform, subscribed ? "PUT" : "DELETE",
                "/v1/account/library/albums/"
                        + TuneWeaveApiClient.encodePathSegment(album.getSourceRef()), Map.of(), null);
    }

    void setArtistSubscribed(Artist artist, boolean subscribed) {
        TuneWeaveReference.require(artist == null ? null : artist.getSourceRef(), "artist");
        TuneWeavePlatform platform = platform(artist.getSourceRef());
        gateway.requestForPlatform(platform, subscribed ? "PUT" : "DELETE",
                "/v1/account/following/artists/"
                        + TuneWeaveApiClient.encodePathSegment(artist.getSourceRef()), Map.of(), null);
    }

    void modifyTracks(Playlist playlist, MusicDetail musicDetail, boolean add) {
        TuneWeaveReference.require(playlist == null ? null : playlist.getSourceRef(), "playlist");
        TuneWeaveReference.require(musicDetail == null ? null : musicDetail.getSourceRef(), "track");
        if (TuneWeaveReference.isBilibiliFavoritesPlaceholder(playlist.getSourceRef()))
            throw new IllegalArgumentException("Create a Bilibili favorite folder before editing tracks");
        if (playlist.getSourceRef().startsWith("account:favorite_tracks:")) {
            setTrackFavorite(musicDetail, add);
            return;
        }
        JsonArray references = new JsonArray();
        references.add(musicDetail.getSourceRef());
        JsonObject body = new JsonObject();
        body.add("refs", references);
        TuneWeavePlatform platform = platform(playlist.getSourceRef());
        String itemPath = "video".equals(musicDetail.getSourceKind()) ? "/videos" : "/tracks";
        gateway.requestForPlatform(platform, add ? "POST" : "DELETE", "/v1/playlists/"
                + TuneWeaveApiClient.encodePathSegment(playlist.getSourceRef()) + itemPath,
                Map.of(), body);
    }

    Playlist create(String name, boolean privatePlaylist) {
        TuneWeavePlatform platform = gateway.defaultPlatform();
        JsonObject body = new JsonObject();
        body.addProperty("platform", platform.apiName());
        body.addProperty("name", name == null ? "" : name.trim());
        body.addProperty("visibility", privatePlaylist ? "private" : "public");
        body.addProperty("kind", "normal");
        JsonObject result = object(gateway.requestForPlatform(
                platform, "POST", "/v1/playlists", Map.of(), body).data());
        JsonElement playlistData = result.get("playlist");
        if (playlistData != null && playlistData.isJsonObject()) {
            return entities.toPlaylist(platform, playlistData.getAsJsonObject());
        }
        String reference = string(result, "playlist_ref", "");
        if (reference.isBlank()) {
            throw new TuneWeaveApiClient.TuneWeaveException(
                    "TuneWeave did not return the created playlist reference", false);
        }
        return catalog.loadPlaylistDetail(reference);
    }

    void update(Playlist playlist, String name, String description) {
        TuneWeaveReference.require(playlist == null ? null : playlist.getSourceRef(), "playlist");
        JsonObject body = new JsonObject();
        if (name != null) {
            body.addProperty("name", name.trim());
        }
        if (description != null) {
            body.addProperty("description", description.trim());
        }
        if (body.isEmpty()) {
            return;
        }
        TuneWeavePlatform platform = platform(playlist.getSourceRef());
        gateway.requestForPlatform(platform, "PATCH", "/v1/playlists/"
                + TuneWeaveApiClient.encodePathSegment(playlist.getSourceRef()), Map.of(), body);
    }

    void delete(Playlist playlist) {
        TuneWeaveReference.require(playlist == null ? null : playlist.getSourceRef(), "playlist");
        TuneWeavePlatform platform = platform(playlist.getSourceRef());
        gateway.requestForPlatform(platform, "DELETE", "/v1/playlists/"
                + TuneWeaveApiClient.encodePathSegment(playlist.getSourceRef()), Map.of(), null);
        entities.removePlaylist(playlist.getId());
    }

    void reorder(List<Playlist> playlists) {
        if (playlists == null || playlists.isEmpty()) {
            return;
        }
        TuneWeavePlatform platform = platform(playlists.getFirst().getSourceRef());
        JsonArray references = new JsonArray();
        for (Playlist playlist : playlists) {
            TuneWeaveReference.require(playlist.getSourceRef(), "playlist");
            if (platform(playlist.getSourceRef()) != platform) {
                throw new IllegalArgumentException("Platform playlist order cannot mix providers");
            }
            references.add(playlist.getSourceRef());
        }
        JsonObject body = new JsonObject();
        body.add("refs", references);
        body.addProperty("platform", platform.apiName());
        gateway.requestForPlatform(
                platform, "PUT", "/v1/account/playlists/order", Map.of(), body);
    }

    void reorderTracks(Playlist playlist, List<MusicDetail> tracks) {
        TuneWeaveReference.require(playlist == null ? null : playlist.getSourceRef(), "playlist");
        JsonArray references = new JsonArray();
        for (MusicDetail track : tracks) {
            TuneWeaveReference.require(track == null ? null : track.getSourceRef(), "track");
            references.add(track.getSourceRef());
        }
        JsonObject body = new JsonObject();
        body.add("refs", references);
        TuneWeavePlatform platform = platform(playlist.getSourceRef());
        gateway.requestForPlatform(platform, "PUT", "/v1/playlists/"
                + TuneWeaveApiClient.encodePathSegment(playlist.getSourceRef()) + "/tracks/order",
                Map.of(), body);
    }

    private TuneWeavePlatform platform(String reference) {
        if (TuneWeaveReference.isBilibiliFavoritesPlaceholder(reference))
            throw new IllegalArgumentException("Bilibili favorites placeholder is not a remote playlist");
        return TuneWeaveReference.platformOrDefault(reference, gateway.defaultPlatform());
    }
}
