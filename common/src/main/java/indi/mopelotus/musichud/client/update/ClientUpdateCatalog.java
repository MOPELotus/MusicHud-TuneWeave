package indi.mopelotus.musichud.client.update;

import com.google.gson.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Only this repository can supply update metadata; catalog entries never choose download hosts or paths. */
public final class ClientUpdateCatalog {
    public static final String REPOSITORY = "MOPELotus/MusicHud-TuneWeave";
    public static final long MAX_JAR_BYTES = 256L * 1024 * 1024;
    public record Installed(String version, String distribution, String loader, String minecraft) {
        public Installed {
            ReleaseVersion parsed = ReleaseVersion.parse(version);
            if (!Set.of("standard", "cf").contains(distribution) || !Set.of("fabric", "neoforge").contains(loader)
                    || !minecraft.matches("[0-9]+(?:\\.[0-9]+){1,2}")
                    || distribution.equals("cf") != (parsed.cfRevision() > 0)) throw new IllegalArgumentException("Invalid installed identity");
        }
    }
    public record Offer(String tag, String version, String distribution, String loader, List<String> minecraft,
                        String file, String sha256, long size, String notes) {
        public Offer { minecraft = List.copyOf(minecraft); }
        public URI downloadUri() { return assetUri(tag, file); }
        public URI releasePageUri() { return ClientUpdateCatalog.releasePageUri(tag); }
    }
    private ClientUpdateCatalog() {}
    public static URI releasePageUri(String tag) {
        validateTag(tag);
        return URI.create("https://github.com/" + REPOSITORY + "/releases/tag/" + encode(tag));
    }
    private static void validateTag(String tag) {
        if (tag == null || !tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) throw new IllegalArgumentException("Invalid tag");
        ReleaseVersion.parse(tag.substring(1));
    }
    public static URI assetUri(String tag, String file) {
        if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")) throw new IllegalArgumentException("Invalid tag");
        ReleaseVersion.parse(tag.substring(1));
        if (!file.matches("[A-Za-z0-9_.+-]{1,180}")) throw new IllegalArgumentException("Invalid asset name");
        return URI.create("https://github.com/" + REPOSITORY + "/releases/download/" + encode(tag) + "/" + encode(file));
    }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    public static Optional<Offer> select(String json, String tag, Installed installed, boolean allowPrerelease, String notes) {
        if (json.length() > 1_048_576) throw new IllegalArgumentException("Catalog too large");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (integer(root, "schema") != 1 || !REPOSITORY.equals(string(root, "repository"))
                || !tag.equals(string(root, "tag")) || !installed.distribution().equals(string(root, "distribution")))
            throw new IllegalArgumentException("Catalog identity mismatch");
        JsonArray rows = root.getAsJsonArray("artifacts");
        if (rows.size() > 64) throw new IllegalArgumentException("Too many catalog artifacts");
        Offer best = null; Set<String> names = new HashSet<>();
        for (JsonElement element : rows) {
            JsonObject row = element.getAsJsonObject();
            String version = string(row, "version"), distribution = string(row, "distribution"), loader = string(row, "loader");
            ReleaseVersion parsed = ReleaseVersion.parse(version);
            if (!distribution.equals(installed.distribution()) || !Set.of("fabric", "neoforge").contains(loader)
                    || distribution.equals("cf") != (parsed.cfRevision() > 0)) throw new IllegalArgumentException("Mixed catalog identity");
            String base = version.split("\\+", -1)[0].replaceFirst("-cf\\.[0-9]+$", "");
            if (!("v" + base).equals(tag)) throw new IllegalArgumentException("Version does not match release tag");
            String file = string(row, "file");
            if (!file.equals("musichud-tuneweave-" + loader + "-" + version + ".jar") || !names.add(file))
                throw new IllegalArgumentException("Unexpected or duplicate JAR name");
            assetUri(tag, file);
            String hash = string(row, "sha256"); long size = integer(row, "size");
            if (!hash.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_JAR_BYTES) throw new IllegalArgumentException("Invalid JAR integrity metadata");
            List<String> minecraft = new ArrayList<>();
            JsonArray games = row.getAsJsonArray("minecraft");
            if (games.isEmpty() || games.size() > 32) throw new IllegalArgumentException("Invalid game version list");
            for (JsonElement game : games) {
                if (!game.isJsonPrimitive() || !game.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid game version type");
                String value = game.getAsString(); if (!value.matches("[0-9]+(?:\\.[0-9]+){1,2}")) throw new IllegalArgumentException("Invalid game version");
                if (minecraft.contains(value)) throw new IllegalArgumentException("Duplicate game version");
                minecraft.add(value);
            }
            if (!loader.equals(installed.loader()) || !minecraft.contains(installed.minecraft())
                    || !allowPrerelease && !parsed.pre().isEmpty() || parsed.compareTo(ReleaseVersion.parse(installed.version())) <= 0) continue;
            Offer offer = new Offer(tag, version, distribution, loader, minecraft, file, hash, size,
                    notes == null ? "" : notes.substring(0, Math.min(notes.length(), 12_000)));
            if (best != null) throw new IllegalArgumentException("Ambiguous updates for this client");
            best = offer;
        }
        return Optional.ofNullable(best);
    }
    private static long integer(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]{1,18}")) throw new IllegalArgumentException("Invalid integer field");
        return Long.parseLong(value.getAsString());
    }
    static String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Missing text field");
        return value.getAsString();
    }
}
