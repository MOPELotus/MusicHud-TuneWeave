package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import indi.mopelotus.musichud.beans.music.Lyric;
import indi.mopelotus.musichud.beans.music.LyricInfo;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.bool;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.elements;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.integer;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.longValue;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.object;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.requiredString;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.string;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.stringMap;
import static indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveJson.unwrap;

/** Owns the NetEase cloud library lifecycle and file transfer protocol. */
final class TuneWeaveCloudService {
    private static final long MAX_UPLOAD_SIZE = 500L * 1024 * 1024;

    private final TuneWeaveGateway gateway;
    private final TuneWeaveAuthenticationService authentication;
    private final TuneWeaveEntityMapper entities;
    private final PagedCollectionLoader pages;
    void clear() { pages.clear(); }

    TuneWeaveCloudService(TuneWeaveGateway gateway,
                          TuneWeaveAuthenticationService authentication,
                          TuneWeaveEntityMapper entities) {
        this.gateway = Objects.requireNonNull(gateway);
        this.authentication = Objects.requireNonNull(authentication);
        this.entities = Objects.requireNonNull(entities);
        pages = new PagedCollectionLoader(gateway, entities);
    }

    TuneWeaveCloudLibrary loadLibrary() { return loadLibrary(false, ignored -> {}); }

    TuneWeaveCloudLibrary loadLibrary(boolean refresh, java.util.function.Consumer<TuneWeaveCloudLibrary> progress) {
        TuneWeavePlatform platform = cloudPlatform();
        var result = new java.util.concurrent.atomic.AtomicReference<TuneWeaveCloudLibrary>();
        pages.load(platform, "/v1/account/cloud/tracks", Map.of("platform", platform.apiName()), refresh, false,
                raw -> entities.toCloudTrack(platform, unwrap(raw)), (tracks, meta) -> {
                    JsonObject pagination = unwrap(meta.get("pagination"));
                    JsonObject extensions = unwrap(pagination.get("extensions"));
                    long total = optionalSize(pagination, "total");
                    if (total >= 0 && total < tracks.size()) throw new IllegalArgumentException("Cloud total is smaller than loaded items");
                    var value = new TuneWeaveCloudLibrary(tracks, total < 0 ? tracks.size() : total,
                            optionalSize(extensions, "storage_size"), optionalSize(extensions, "storage_max_size"));
                    result.set(value); progress.accept(value);
                });
        return result.get();
    }

    private static long optionalSize(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return -1;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid cloud size");
        try {
            long size = value.getAsBigDecimal().longValueExact();
            if (size < 0) throw new IllegalArgumentException("Negative cloud size");
            return size;
        } catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid cloud size", error); }
    }

    void deleteTrack(TuneWeaveCloudTrack cloudTrack) {
        TuneWeaveReference.require(cloudTrack == null ? null : cloudTrack.reference(), "cloud track");
        TuneWeavePlatform platform = TuneWeaveReference.platform(cloudTrack.reference());
        JsonArray references = new JsonArray();
        references.add(cloudTrack.reference());
        JsonObject body = new JsonObject();
        body.add("refs", references);
        gateway.requestForPlatform(platform, "DELETE", "/v1/account/cloud/tracks", Map.of(), body);
    }

    String uploadTrack(Path file, String songName, String artist, String album) {
        Objects.requireNonNull(file, "file");
        TuneWeavePlatform platform = cloudPlatform();
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Cloud upload must be a file");
            }
            long size = Files.size(file);
            if (size <= 0 || size > MAX_UPLOAD_SIZE) {
                throw new IllegalArgumentException("Cloud upload file must be between 1 byte and 500 MiB");
            }
            String filename = file.getFileName().toString();
            String contentType = contentType(file);
            String md5 = digestFile(file, "MD5");
            long bitrate = 999_000L;

            JsonObject ticketBody = new JsonObject();
            ticketBody.addProperty("md5", md5);
            ticketBody.addProperty("file_size", size);
            ticketBody.addProperty("filename", filename);
            ticketBody.addProperty("bitrate", bitrate);
            ticketBody.addProperty("content_type", contentType);
            JsonObject ticket = object(gateway.requestForPlatform(
                    platform, "POST", "/v1/account/cloud/uploads/ticket",
                    Map.of("platform", platform.apiName()), ticketBody).data());
            String provisionalTrackId = requiredString(ticket, "provisional_track_id");
            String resourceId = requiredString(ticket, "resource_id");
            if (bool(ticket, "upload_required", true)) {
                TuneWeaveApiClient.uploadTicketFile(requiredString(ticket, "upload_url"),
                        requiredString(ticket, "upload_method"),
                        stringMap(ticket.get("upload_headers")), file);
            }

            JsonObject completion = new JsonObject();
            completion.addProperty("provisional_track_id", provisionalTrackId);
            completion.addProperty("resource_id", resourceId);
            completion.addProperty("md5", md5);
            completion.addProperty("filename", filename);
            completion.addProperty("bitrate", bitrate);
            addTrimmed(completion, "song_name", songName);
            addTrimmed(completion, "artist", artist);
            addTrimmed(completion, "album", album);
            JsonObject data = object(gateway.requestForPlatform(
                    platform, "POST", "/v1/account/cloud/uploads/complete",
                    Map.of("platform", platform.apiName()), completion).data());
            return string(data, "track_ref", "");
        } catch (IOException error) {
            throw new IllegalArgumentException("Failed to read the selected cloud upload file", error);
        }
    }

    String importTrack(String md5, String sourceTrackId, long bitrate, long fileSize,
                       String fileType, String songName, String artist, String album) {
        TuneWeavePlatform platform = cloudPlatform();
        JsonObject body = new JsonObject();
        body.addProperty("md5", trimmed(md5));
        if (sourceTrackId != null && !sourceTrackId.isBlank()) {
            body.addProperty("source_track_id", sourceTrackId.trim());
        }
        body.addProperty("bitrate", bitrate);
        body.addProperty("file_size", fileSize);
        body.addProperty("file_type", trimmed(fileType));
        body.addProperty("song_name", trimmed(songName));
        body.addProperty("artist", trimmed(artist));
        body.addProperty("album", trimmed(album));
        JsonObject data = object(gateway.requestForPlatform(
                platform, "POST", "/v1/account/cloud/imports",
                Map.of("platform", platform.apiName()), body).data());
        return string(data, "track_ref", "");
    }

    boolean matchTrack(TuneWeaveCloudTrack cloudTrack, String targetTrackId) {
        TuneWeaveReference.require(cloudTrack == null ? null : cloudTrack.reference(), "cloud track");
        TuneWeavePlatform platform = TuneWeaveReference.platform(cloudTrack.reference());
        JsonObject body = new JsonObject();
        body.addProperty("user_id", cloudUserId(platform));
        body.addProperty("cloud_track_id", TuneWeaveReference.id(cloudTrack.reference()));
        body.addProperty("target_track_id", targetTrackId == null || targetTrackId.isBlank()
                ? "0" : TuneWeaveReference.id(targetTrackId.trim()));
        JsonObject data = object(gateway.requestForPlatform(
                platform, "POST", "/v1/account/cloud/matches",
                Map.of("platform", platform.apiName()), body).data());
        return bool(data, "matched", false);
    }

    LyricInfo loadLyrics(TuneWeaveCloudTrack cloudTrack) {
        TuneWeaveReference.require(cloudTrack == null ? null : cloudTrack.reference(), "cloud track");
        return loadLyrics(cloudTrack.reference());
    }

    TuneWeaveCloudTrack loadTrack(String cloudReference) {
        TuneWeaveReference.require(cloudReference, "cloud track");
        return loadLibrary().tracks().stream()
                .filter(track -> cloudReference.equals(track.reference()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "TuneWeave cloud track is no longer available"));
    }

    LyricInfo loadLyrics(String cloudReference) {
        TuneWeaveReference.require(cloudReference, "cloud track");
        TuneWeavePlatform platform = TuneWeaveReference.platform(cloudReference);
        JsonObject data = object(gateway.requestForPlatform(
                platform, "GET", "/v1/account/cloud/lyrics", Map.of(
                        "platform", platform.apiName(), "user_id", cloudUserId(platform),
                        "sid", TuneWeaveReference.id(cloudReference)), null).data());
        return new LyricInfo(new Lyric(string(data, "plain", "")),
                new Lyric(string(data, "translated", "")),
                new Lyric(string(data, "word_synced", "")),
                new Lyric(string(data, "romanized", "")));
    }

    void downloadTrack(TuneWeaveCloudTrack cloudTrack, Path target) {
        TuneWeaveReference.require(cloudTrack == null ? null : cloudTrack.reference(), "cloud track");
        Objects.requireNonNull(target, "target");
        TuneWeavePlatform platform = TuneWeaveReference.platform(cloudTrack.reference());
        JsonObject data = object(gateway.requestForPlatform(
                platform, "GET", "/v1/account/cloud/tracks/"
                        + TuneWeaveApiClient.encodePathSegment(cloudTrack.reference()) + "/download",
                Map.of(), null).data());
        if (!bool(data, "available", !string(data, "url", "").isBlank())) {
            throw new IllegalArgumentException("Cloud source file is not available for download");
        }
        TuneWeaveApiClient.downloadMediaFile(requiredString(data, "url"),
                stringMap(data.get("headers")), target);
    }

    private TuneWeavePlatform cloudPlatform() {
        TuneWeavePlatform platform = gateway.defaultPlatform();
        if (platform != TuneWeavePlatform.NETEASE) {
            throw new IllegalArgumentException(
                    "Cloud library is only available for NetEase Music accounts");
        }
        return platform;
    }

    private String cloudUserId(TuneWeavePlatform platform) {
        TuneWeaveSession profile = authentication.loadSession(platform);
        if (profile == null || profile.userId() == null || profile.userId().isBlank()) {
            throw new IllegalArgumentException("TuneWeave session did not return a cloud user ID");
        }
        return profile.userId();
    }

    private static String contentType(Path file) throws IOException {
        String detected = Files.probeContentType(file);
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        String filename = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".flac")) return "audio/flac";
        if (filename.endsWith(".mp3")) return "audio/mpeg";
        if (filename.endsWith(".m4a") || filename.endsWith(".mp4")) return "audio/mp4";
        if (filename.endsWith(".ogg") || filename.endsWith(".opus")) return "audio/ogg";
        if (filename.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private static String digestFile(Path file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void addTrimmed(JsonObject target, String property, String value) {
        if (value != null && !value.isBlank()) {
            target.addProperty(property, value.trim());
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
