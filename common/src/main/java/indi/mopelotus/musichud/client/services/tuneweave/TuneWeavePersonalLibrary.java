package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.util.List;
import java.util.Map;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.*;

/** Additional personal catalogs preserve resource kinds and purchase occurrences. */
public final class TuneWeavePersonalLibrary {
    public enum Kind {
        DIGITAL_ALBUMS("account_digital_albums", "/v1/account/library/digital-albums"),
        PURCHASED_TRACKS("account_purchased_tracks", "/v1/account/purchases/tracks"),
        PURCHASED_ALBUMS("account_purchased_albums", "/v1/account/purchases/albums"),
        SUBMISSIONS("account_playlist_submissions", "/v1/account/playlist-submissions");
        public final String capability;
        final String path;
        Kind(String capability, String path) { this.capability = capability; this.path = path; }
        public String key() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public record Entry(long occurrence, String reference, String resourceKind, String name,
                        String coverUrl, String status, Object resource) {
        public TuneWeavePlatform platform() { return TuneWeaveReference.platform(reference); }
    }
    private TuneWeavePersonalLibrary() {}
    static List<Entry> load(TuneWeaveGateway gateway, TuneWeaveEntityMapper entities, TuneWeavePlatform platform, Kind kind) {
        var capabilities = TuneWeaveCapabilities.read(gateway.requestWithoutCredential("GET", "/v1/capabilities", Map.of(), null).data(), platform.apiName());
        if (!capabilities.contains(kind.capability)) throw new indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException(
                "Personal library is unavailable", 422, "capability_not_supported", false, com.google.gson.JsonNull.INSTANCE);
        var raw = OffsetPagination.loadAll(offset -> gateway.requestForPlatform(platform, "GET", kind.path,
                Map.of("platform", platform.apiName(), "limit", "100", "offset", Integer.toString(offset)), null));
        var result = new java.util.ArrayList<Entry>();
        for (JsonElement item : raw) result.add(parse(entities, platform, kind, result.size(), object(item)));
        return List.copyOf(result);
    }
    static TuneWeaveSubmissionOutcome submit(TuneWeaveGateway gateway, String reference, boolean deletion) {
        if (TuneWeaveReference.platform(reference) != TuneWeavePlatform.KUWO
                || reference.substring(reference.indexOf(':') + 1).isBlank())
            throw new IllegalArgumentException("Invalid submission playlist reference");
        String encoded = indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.encodePathSegment(reference);
        var response = gateway.requestForPlatform(TuneWeavePlatform.KUWO, deletion ? "DELETE" : "POST",
                deletion ? "/v1/account/playlist-submissions/" + encoded : "/v1/playlists/" + encoded + "/submission",
                Map.of(), deletion ? null : new JsonObject());
        return TuneWeaveSubmissionOutcome.read(object(response.data()), reference, deletion);
    }
    static void setDigitalSubscribed(TuneWeaveGateway gateway, Entry entry, boolean subscribed) {
        if (entry == null || !"digital_album".equals(entry.resourceKind()) || entry.platform() != TuneWeavePlatform.MIGU)
            throw new IllegalArgumentException("Unsupported digital album subscription");
        gateway.requestForPlatform(entry.platform(), subscribed ? "PUT" : "DELETE",
                "/v1/account/library/digital-albums/"
                        + indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.encodePathSegment(entry.reference()),
                Map.of(), null);
    }
    static Entry parse(TuneWeaveEntityMapper entities, TuneWeavePlatform platform, Kind kind, long occurrence, JsonObject raw) {
        JsonObject resource = raw;
        String resourceKind = "digital_album";
        String status = "";
        Object mapped = null;
        if (kind == Kind.PURCHASED_TRACKS || kind == Kind.PURCHASED_ALBUMS) {
            resourceKind = kind == Kind.PURCHASED_TRACKS ? "track" : "album";
            JsonElement nested = raw.get(resourceKind);
            if (kind == Kind.PURCHASED_ALBUMS && raw.has("digital_album") && !raw.get("digital_album").isJsonNull()) {
                if (nested != null && !nested.isJsonNull()) throw new IllegalArgumentException("Ambiguous purchased album kind");
                resourceKind = "digital_album"; nested = raw.get(resourceKind);
            }
            if (nested == null || nested.isJsonNull())
                return new Entry(occurrence, "", "unresolved", string(raw, "name"), imageUrl(raw, "cover_url"), "unresolved", null);
            if (!nested.isJsonObject()) throw new IllegalArgumentException("Invalid purchased resource");
            resource = nested.getAsJsonObject();
        }
        String reference;
        if (kind == Kind.SUBMISSIONS) {
            reference = requiredString(raw, "playlist_ref"); resourceKind = "playlist";
            status = requiredString(raw, "review_status");
            if (!java.util.Set.of("pending", "approved", "rejected", "unknown").contains(status)) status = "unknown";
        } else reference = requiredString(resource, "ref");
        if (TuneWeaveReference.platform(reference) != platform) throw new IllegalArgumentException("Personal library platform mismatch");
        if (resourceKind.equals("track")) mapped = entities.toTrack(platform, resource);
        else if (resourceKind.equals("album")) mapped = entities.toAlbum(platform, resource);
        String cover = imageUrl(resource, "cover_url");
        if (cover.isBlank() && mapped instanceof MusicDetail track) cover = track.getAlbum().getPicUrl();
        if (cover.isBlank()) cover = imageUrl(raw, "cover_url");
        return new Entry(occurrence, reference, resourceKind, string(resource, "name"), cover, status, mapped);
    }
}
