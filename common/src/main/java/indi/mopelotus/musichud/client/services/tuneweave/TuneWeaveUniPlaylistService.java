package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Album;
import indi.mopelotus.musichud.beans.music.Artist;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.MusicCollection;
import indi.mopelotus.musichud.beans.music.PusherInfo;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.services.music.AccountScope;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import indi.mopelotus.musichud.utils.collections.ObservableSequencedSet;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.elements;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.integer;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.object;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.string;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.unwrap;

/** Owns client-local aggregate playlists and TuneWeave materialization calls. */
final class TuneWeaveUniPlaylistService {
    private final TuneWeaveGateway gateway;
    private final TuneWeaveEntityMapper entities;
    private final LocalUniPlaylistStore store;
    private final java.util.function.Function<String, Playlist> knownPlaylist;
    private final java.util.function.Function<String, Album> knownAlbum;

    TuneWeaveUniPlaylistService(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities,
                                LocalUniPlaylistStore store) {
        this(gateway, entities, store, reference -> null);
    }

    TuneWeaveUniPlaylistService(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities,
                                LocalUniPlaylistStore store, java.util.function.Function<String, Playlist> knownPlaylist) {
        this(gateway, entities, store, knownPlaylist, reference -> null);
    }

    TuneWeaveUniPlaylistService(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities,
                                LocalUniPlaylistStore store, java.util.function.Function<String, Playlist> knownPlaylist,
                                java.util.function.Function<String, Album> knownAlbum) {
        this.gateway = Objects.requireNonNull(gateway);
        this.entities = Objects.requireNonNull(entities);
        this.store = Objects.requireNonNull(store);
        this.knownPlaylist = Objects.requireNonNull(knownPlaylist);
        this.knownAlbum = Objects.requireNonNull(knownAlbum);
    }

    List<TuneWeaveUniPlaylist> list() {
        List<TuneWeaveUniPlaylist> result = new ArrayList<>();
        for (JsonObject document : store.list()) {
            result.add(playlist(document));
        }
        return result;
    }

    TuneWeaveUniPlaylist create(String name, String description) {
        return playlist(store.create(name, description));
    }

    TuneWeaveUniPlaylist update(String reference, String name, String description) {
        return playlist(store.update(reference, name, description));
    }

    void delete(String reference) {
        store.delete(reference);
    }

    List<TuneWeaveUniItem> items(String reference) {
        List<TuneWeaveUniItem> result = new ArrayList<>();
        for (JsonObject value : store.items(reference)) {
            JsonObject snapshot = value.has("snapshot") && value.get("snapshot").isJsonObject()
                    ? value.getAsJsonObject("snapshot") : new JsonObject();
            String itemReference = string(value, "source_ref", "");
            if (itemReference.isBlank()) {
                continue;
            }
            List<String> artists = new ArrayList<>();
            JsonElement artistData = snapshot.get("artists");
            if (artistData != null && artistData.isJsonArray()) {
                artistData.getAsJsonArray().forEach(item -> artists.add(item.getAsString()));
            }
            result.add(new TuneWeaveUniItem(string(value, "id", ""),
                    integer(value, "position", result.size()), string(value, "kind", "track"),
                    itemReference, string(snapshot, "title", itemReference), artists,
                    string(snapshot, "album", ""), integer(snapshot, "duration_ms", 0),
                    string(snapshot, "cover_url", "")));
        }
        return result;
    }

    MusicDetail itemTrack(TuneWeaveUniItem item) {
        TuneWeaveReference.require(item == null ? null : item.sourceRef(), "Uni Playlist item");
        TuneWeavePlatform platform = TuneWeaveReference.platformOrDefault(
                item.sourceRef(), gateway.defaultPlatform());
        MusicDetail cached = entities.track(entities.accountScope(platform), item.sourceRef());
        if (cached != null && cached.getDurationMillis() > 0) {
            MusicDetail reused = MusicDetail.fromTuneWeave(cached.getId(), cached.getSourceRef(),
                    cached.getSourceKind(), cached.getName(), cached.getDurationMillis(),
                    cached.getAlbum(), cached.getArtists());
            reused.setClientHostedUni(true);
            return reused;
        }
        List<Artist> artists = item.artists().stream()
                .map(name -> new Artist(entities.stableId(platform, "uni-artist:" + name), name,
                        "", 0, 0, "", new ArrayList<>(), 0, ""))
                .toList();
        String albumName = item.album().isBlank() ? item.title() : item.album();
        Album album = new Album(entities.stableId(platform, "uni-album:" + item.sourceRef()),
                albumName, item.coverUrl().isBlank() ? MusicHud.ICON_BASE64 : item.coverUrl(),
                I18n.get(MusicHud.MOD_ID + ".text.uniPlaylist.name"), "", 0,
                new ObservableSequencedSet<>(), new LinkedHashSet<>(artists), PusherInfo.EMPTY, "");
        String sourceKind = "mv".equals(item.kind()) ? "video" : item.kind();
        int duration = item.durationMillis();
        if (duration <= 0 && "radio_station".equals(sourceKind)) {
            duration = 24 * 60 * 60 * 1000;
        }
        if (duration <= 0) {
            throw new IllegalArgumentException("Uni Playlist item has no playable duration");
        }
        MusicDetail track = MusicDetail.fromTuneWeave(
                entities.stableId(platform, "uni-item:" + item.id() + ':' + item.sourceRef()),
                item.sourceRef(), sourceKind, item.title(), duration, album, artists);
        track.setClientHostedUni(true);
        track.setPusherInfo(PusherInfo.EMPTY);
        entities.cacheTrack(track);
        return track;
    }

    void addItems(String reference, List<String> resourceReferences) {
        if (resourceReferences == null || resourceReferences.isEmpty()) {
            return;
        }
        List<String> references = resourceReferences.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(100)
                .toList();
        if (!references.isEmpty()) store.append(reference, materializeRequestedItems(references, "track"));
    }

    void addItem(String reference, String resourceReference, String kind) {
        if (resourceReference == null || resourceReference.isBlank()) {
            return;
        }
        store.append(reference, materializeRequestedItems(List.of(resourceReference),
                kind == null || kind.isBlank() ? "track" : kind));
    }

    /** Flush missing runs in place so local and remote items retain caller order. */
    List<JsonObject> materializeRequestedItems(List<String> references, String kind) {
        List<JsonObject> result = new ArrayList<>();
        JsonArray missing = new JsonArray();
        for (String reference : references) {
            TuneWeaveReference.require(reference, "Uni Playlist item");
            TuneWeavePlatform platform = TuneWeaveReference.platformOrDefault(reference, gateway.defaultPlatform());
            MusicDetail cached = entities.track(entities.accountScope(platform), reference);
            if (cached != null && cached.getDurationMillis() > 0 && kind.equals(cached.getSourceKind())
                    && !cached.isCloudSource() && !cached.isClientHostedUni()) {
                appendMissingItems(result, missing);
                result.add(materializeLocalTrack(cached, result.size()));
            } else {
                missing.add(materializeItem(reference, kind));
            }
        }
        appendMissingItems(result, missing);
        for (int i = 0; i < result.size(); i++) result.get(i).addProperty("position", i);
        return result;
    }

    private void appendMissingItems(List<JsonObject> result, JsonArray missing) {
        if (missing.isEmpty()) return;
        JsonObject body = new JsonObject();
        body.add("items", missing.deepCopy());
        result.addAll(materializeItems(body));
        while (!missing.isEmpty()) missing.remove(missing.size() - 1);
    }

    void deleteItem(String reference, String itemId) {
        store.removeItem(reference, itemId);
    }

    void reorderItems(String reference, List<String> itemIds) {
        store.reorder(reference, itemIds);
    }

    TuneWeaveUniPlaylist importPlaylists(String name, List<String> sourceReferences) {
        List<TuneWeaveUniImportSource> sources = sourceReferences.stream()
                .filter(value -> value != null && !value.isBlank())
                .limit(50)
                .map(TuneWeaveUniPlaylistService::playlistSource)
                .toList();
        return importSources(name, null, sources);
    }

    TuneWeaveUniPlaylist importSources(String name, List<TuneWeaveUniImportSource> sources) {
        return importSources(name, null, sources);
    }

    TuneWeaveUniPlaylist importSources(String name, String description,
                                       List<TuneWeaveUniImportSource> sources) {
        MaterializedImport materialized = materializeImportSources(sources);
        String playlistName = name == null || name.isBlank() ? materialized.name() : name;
        String playlistDescription = description == null ? materialized.description() : description;
        TuneWeaveUniPlaylist created = create(playlistName, playlistDescription);
        try {
            return playlist(store.append(created.reference(), materialized.items()));
        } catch (RuntimeException error) {
            store.delete(created.reference());
            throw error;
        }
    }

    TuneWeaveUniPlaylist appendSources(String reference, List<TuneWeaveUniImportSource> sources) {
        TuneWeaveReference.require(reference, "local Uni Playlist");
        return playlist(store.append(reference, materializeImportSources(sources).items()));
    }

    JsonObject exportDocument(String reference) {
        return store.exportDocument(reference);
    }

    TuneWeaveUniPlaylist importDocument(JsonObject document) {
        return playlist(store.importDocument(Objects.requireNonNull(document)));
    }

    MaterializedImport materializeImportSources(List<TuneWeaveUniImportSource> sources) {
        if (sources == null || sources.isEmpty() || sources.size() > 50) {
            throw new IllegalArgumentException("Import requires between 1 and 50 sources");
        }
        for (var source : sources) {
            if (source == null || source.platform() == null || source.type() == null || source.type().isBlank()
                    || source.id() == null || source.id().isBlank()) throw new IllegalArgumentException("Invalid import source");
            TuneWeavePlatform.fromApiName(source.platform());
        }
        List<JsonObject> result = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        String name = "", description = "";
        int sourceIndex = 0;
        for (var source : sources) {
            String sourceReference = source.platform() + ":" + source.id();
            MusicCollection local = switch (source.type()) {
                case "playlist" -> knownPlaylist.apply(sourceReference);
                case "album" -> knownAlbum.apply(sourceReference);
                default -> null;
            };
            MaterializedImport materialized;
            if (local != null) {
                List<JsonObject> items = new ArrayList<>();
                for (MusicDetail track : local.getMusicDetails()) items.add(materializeLocalTrack(track, items.size()));
                materialized = new MaterializedImport(local.getName(), "", items);
            } else {
                materialized = materializeRemoteSources(List.of(source));
            }
            if (name.isBlank()) name = materialized.name();
            if (description.isBlank()) description = materialized.description();
            for (JsonObject item : materialized.items()) {
                String reference = string(item, "source_ref", "");
                if (reference.isBlank()) throw new IllegalArgumentException("Materialized item is missing source_ref");
                if (seen.add(reference)) {
                    if (result.size() >= 100_000) throw new IllegalArgumentException("Too many imported items");
                    item.addProperty("position", result.size());
                    JsonObject extensions = item.has("extensions") && item.get("extensions").isJsonObject()
                            ? item.getAsJsonObject("extensions") : new JsonObject();
                    extensions.addProperty("import_source_index", sourceIndex);
                    extensions.addProperty("import_source_ref", source.platform() + ":" + source.id());
                    extensions.addProperty("import_source_type", source.type());
                    item.add("extensions", extensions);
                    result.add(item);
                }
            }
            sourceIndex++;
        }
        return new MaterializedImport(name, description, result);
    }

    private MaterializedImport materializeRemoteSources(List<TuneWeaveUniImportSource> sources) {
        JsonArray sourceArray = new JsonArray();
        sources.stream().limit(50).forEach(source -> {
            JsonObject value = new JsonObject();
            value.addProperty("platform", source.platform());
            value.addProperty("type", source.type());
            value.addProperty("id", source.id());
            sourceArray.add(value);
        });
        JsonObject body = new JsonObject();
        body.add("sources", sourceArray);
        List<JsonObject> materialized = new ArrayList<>();
        String materializedName = "";
        String materializedDescription = "";
        int offset = 0;
        int total;
        do {
            JsonObject data = object(gateway.requestWithAllCredentials(
                    "POST", "/v1/uni/materialize/imports",
                    Map.of("limit", "500", "offset", Integer.toString(offset)), body).data());
            if (materializedName.isBlank()) {
                materializedName = string(data, "name", "");
            }
            if (materializedDescription.isBlank()) {
                materializedDescription = string(data, "description", "");
            }
            List<JsonElement> page = elements(data);
            for (JsonElement value : page) {
                materialized.add(unwrap(value).deepCopy());
            }
            JsonElement count = data.get("item_count");
            try {
                if (count == null || !count.isJsonPrimitive() || !count.getAsJsonPrimitive().isNumber()) {
                    throw new IllegalArgumentException("Missing materialized item count");
                }
                total = count.getAsBigDecimal().intValueExact();
            } catch (ArithmeticException | NumberFormatException error) {
                throw new IllegalArgumentException("Invalid materialized item count", error);
            }
            if (total < 0 || total > 100_000 || materialized.size() > 100_000) {
                throw new IllegalArgumentException("Invalid materialized item count");
            }
            offset += page.size();
            if (page.isEmpty()) {
                if (offset < total) throw new IllegalArgumentException("Materialization ended before all items arrived");
                break;
            }
        } while (offset < total);
        return new MaterializedImport(materializedName, materializedDescription, materialized);
    }

    static JsonObject materializeLocalTrack(MusicDetail track, int position) {
        Objects.requireNonNull(track);
        if (position < 0 || track == MusicDetail.NONE || track.getSourceRef().isBlank()) {
            throw new IllegalArgumentException("Local track identity or position is invalid");
        }
        JsonObject item = new JsonObject();
        item.addProperty("source_ref", track.getSourceRef());
        item.addProperty("kind", track.getSourceKind());
        item.addProperty("position", position);
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("title", track.getName());
        snapshot.addProperty("duration_ms", track.getDurationMillis());
        snapshot.addProperty("album", track.getAlbum().getName());
        snapshot.addProperty("cover_url", track.getAlbum().getThumbnailPicUrl(240));
        JsonArray artists = new JsonArray();
        track.getArtists().stream().map(Artist::getName).forEach(artists::add);
        snapshot.add("artists", artists);
        item.add("snapshot", snapshot);
        return item;
    }

    private List<JsonObject> materializeItems(JsonObject body) {
        JsonObject data = object(gateway.requestWithAllCredentials(
                "POST", "/v1/uni/materialize/items", Map.of(), body).data());
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement value : elements(data)) {
            result.add(unwrap(value).deepCopy());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("TuneWeave did not materialize any playlist items");
        }
        return result;
    }

    private static JsonObject materializeItem(String reference, String kind) {
        JsonObject item = new JsonObject();
        item.addProperty("ref", reference);
        item.addProperty("kind", kind);
        return item;
    }

    private static TuneWeaveUniImportSource playlistSource(String reference) {
        int separator = reference.indexOf(':');
        if (separator <= 0 || separator + 1 >= reference.length()) {
            throw new IllegalArgumentException("TuneWeave playlist reference is invalid");
        }
        return new TuneWeaveUniImportSource(
                reference.substring(0, separator), "playlist", reference.substring(separator + 1));
    }

    private static TuneWeaveUniPlaylist playlist(JsonObject document) {
        String reference = LocalUniPlaylistStore.reference(document);
        return new TuneWeaveUniPlaylist(reference, string(document, "name", reference),
                string(document, "description", ""), integer(document, "item_count", 0));
    }

    record MaterializedImport(String name, String description, List<JsonObject> items) {
    }
}
