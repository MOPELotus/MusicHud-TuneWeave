package indi.mopelotus.musichud.platform.mod.config;

import com.google.gson.*;
import indi.mopelotus.musichud.beans.user.SavedAccount;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import java.io.IOException;
import java.io.StringReader;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.function.Consumer;
import static java.nio.file.StandardOpenOption.*;

/** Client-owned private data beside the API executable, never in the exportable config. */
final class ClientAccountStore {
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private final Path file;
    private JsonObject root;

    ClientAccountStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
        try {
            if (Files.isSymbolicLink(this.file)) throw new IOException();
            if (Files.exists(this.file)) {
                if (Files.size(this.file) > MAX_FILE_BYTES) throw new IOException();
                byte[] bytes;
                try (var input = Files.newInputStream(this.file, LinkOption.NOFOLLOW_LINKS)) {
                    bytes = input.readNBytes(MAX_FILE_BYTES + 1);
                }
                if (bytes.length > MAX_FILE_BYTES) throw new IOException();
                String json = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .decode(ByteBuffer.wrap(bytes)).toString();
                root = parse(json).getAsJsonObject();
                validate(root);
            } else {
                root = new JsonObject(); root.addProperty("version", 1); root.add("platforms", new JsonObject());
            }
        } catch (IOException | RuntimeException error) {
            // Parser exception text can echo private JSON; deliberately do not retain its cause.
            throw new IllegalStateException("Cannot read private account storage; existing files were preserved");
        }
    }

    synchronized boolean isPristine() {
        return root.size() == 2 && root.getAsJsonObject("platforms").isEmpty() && !root.has("profile");
    }

    synchronized String credential(String platform) {
        JsonObject account = active(root, platform);
        return account == null ? "" : text(account, "credential");
    }

    synchronized List<SavedAccount> accounts(String platform) {
        JsonObject state = platform(root, platform, false);
        if (state == null) return List.of();
        var result = new ArrayList<SavedAccount>();
        for (var value : state.getAsJsonArray("accounts")) {
            JsonObject account = value.getAsJsonObject();
            result.add(new SavedAccount(text(account, "id"), text(account, "userId"), text(account, "nickname"),
                    text(account, "avatarUrl"), text(account, "id").equals(text(state, "active"))));
        }
        return List.copyOf(result);
    }

    synchronized void setCredential(String platform, String credential, boolean newLogin) {
        if (credential == null || credential.isBlank()) { removeActive(platform); return; }
        mutate(next -> {
            JsonObject state = platform(next, platform, true);
            JsonObject account = newLogin ? null : active(next, platform);
            if (account == null) {
                account = new JsonObject(); account.addProperty("id", UUID.randomUUID().toString());
                state.getAsJsonArray("accounts").add(account);
                state.addProperty("active", text(account, "id"));
            }
            account.addProperty("credential", credential.trim());
        });
    }

    synchronized void loginAccount(String platform, String credential, String userId, String nickname, String avatarUrl) {
        if (credential == null || credential.isBlank()) throw new IllegalArgumentException("Login credential is unavailable");
        String identity = Objects.requireNonNullElse(userId, "");
        mutate(next -> {
            JsonObject state = platform(next, platform, true);
            JsonObject account = null;
            if (!identity.isBlank()) {
                for (JsonElement candidate : state.getAsJsonArray("accounts")) {
                    if (identity.equals(text(candidate.getAsJsonObject(), "userId"))) {
                        account = candidate.getAsJsonObject(); break;
                    }
                }
            }
            if (account == null) {
                account = new JsonObject(); account.addProperty("id", UUID.randomUUID().toString());
                state.getAsJsonArray("accounts").add(account);
            }
            account.addProperty("credential", credential.trim());
            account.addProperty("userId", identity);
            account.addProperty("nickname", Objects.requireNonNullElse(nickname, ""));
            account.addProperty("avatarUrl", Objects.requireNonNullElse(avatarUrl, ""));
            state.addProperty("active", text(account, "id"));
        });
    }

    synchronized void select(String platform, String id) {
        mutate(next -> {
            JsonObject state = platform(next, platform, false);
            if (state == null || find(state, id) == null) throw new IllegalArgumentException("Saved account is unavailable");
            state.addProperty("active", id);
        });
    }

    private void removeActive(String platform) {
        mutate(next -> {
            JsonObject state = platform(next, platform, false);
            if (state == null) return;
            String id = text(state, "active");
            var accounts = state.getAsJsonArray("accounts");
            for (int i = accounts.size() - 1; i >= 0; i--) {
                if (id.equals(text(accounts.get(i).getAsJsonObject(), "id"))) accounts.remove(i);
            }
            // Logout never silently activates another saved identity.
            state.addProperty("active", "");
        });
    }

    synchronized void profile(String platform, String userId, String nickname, String avatarUrl) {
        String identity = Objects.requireNonNullElse(userId, "");
        mutate(next -> {
            JsonObject account = active(next, platform);
            if (account == null) return;
            JsonObject state = platform(next, platform, false);
            JsonObject previous = null;
            for (var value : state.getAsJsonArray("accounts")) {
                var candidate = value.getAsJsonObject();
                if (candidate != account && !identity.isBlank() && identity.equals(text(candidate, "userId"))) { previous = candidate; break; }
            }
            if (previous != null) {
                // Logging into the same user rotates that saved account, rather than creating a duplicate.
                String id = text(previous, "id");
                state.getAsJsonArray("accounts").remove(previous);
                account.addProperty("id", id); state.addProperty("active", id);
            }
            account.addProperty("userId", identity);
            account.addProperty("nickname", Objects.requireNonNullElse(nickname, ""));
            account.addProperty("avatarUrl", Objects.requireNonNullElse(avatarUrl, ""));
        });
    }

    synchronized JsonElement profileData() { return root.has("profile") ? root.get("profile").deepCopy() : JsonNull.INSTANCE; }
    synchronized void profileData(JsonElement profile) {
        JsonElement copy = profile == null ? JsonNull.INSTANCE : parse(profile.toString());
        mutate(next -> next.add("profile", copy));
    }

    synchronized void migrate(String credentials, String profile) {
        JsonObject legacy = credentials == null || credentials.isBlank() ? new JsonObject()
                : parse(credentials).getAsJsonObject();
        JsonElement oldProfile = profile == null || profile.isBlank() ? JsonNull.INSTANCE : parse(profile);
        if (!oldProfile.isJsonNull() && !oldProfile.isJsonObject()) throw new IllegalArgumentException("Invalid legacy profile");
        if (legacy.size() > TuneWeavePlatform.values().length) throw new IllegalArgumentException("Invalid legacy account storage");
        if (legacy.isEmpty() && oldProfile.isJsonNull()) return;
        mutate(next -> {
            for (var entry : legacy.entrySet()) {
                String platform = TuneWeavePlatform.requireApiName(entry.getKey()).apiName();
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Invalid legacy account storage");
                String credential = entry.getValue().getAsString();
                if (credential.isBlank()) continue;
                JsonObject state = platform(next, platform, true);
                boolean duplicate = false;
                for (var value : state.getAsJsonArray("accounts")) duplicate |= credential.equals(text(value.getAsJsonObject(), "credential"));
                if (duplicate) continue;
                JsonObject account = new JsonObject(); account.addProperty("id", UUID.randomUUID().toString());
                account.addProperty("credential", credential); state.getAsJsonArray("accounts").add(account);
                if (text(state, "active").isBlank()) state.addProperty("active", text(account, "id"));
            }
            if ((!next.has("profile") || next.get("profile").isJsonNull()) && !oldProfile.isJsonNull()) next.add("profile", oldProfile);
        });
    }

    private void mutate(Consumer<JsonObject> change) {
        JsonObject next = root.deepCopy(); change.accept(next); validate(next);
        byte[] bytes = next.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Private account storage exceeds its limit");
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            if (Files.isSymbolicLink(file)) throw new IOException();
            var permissions = file.getFileSystem().supportedFileAttributeViews().contains("posix")
                    ? new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                    : new java.nio.file.attribute.FileAttribute<?>[0];
            temporary = Files.createTempFile(file.getParent(), ".client-accounts-", ".tmp", permissions);
            var acl = Files.getFileAttributeView(temporary, java.nio.file.attribute.AclFileAttributeView.class);
            if (acl != null) {
                var ownerOnly = java.nio.file.attribute.AclEntry.newBuilder()
                        .setType(java.nio.file.attribute.AclEntryType.ALLOW)
                        .setPrincipal(acl.getOwner())
                        .setPermissions(java.nio.file.attribute.AclEntryPermission.values()).build();
                acl.setAcl(List.of(ownerOnly));
            }
            try (var output = FileChannel.open(temporary, WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            root = next;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot save private accounts; previous data was preserved");
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
        }
    }

    private static JsonObject platform(JsonObject root, String platform, boolean create) {
        TuneWeavePlatform.requireApiName(platform);
        JsonObject platforms = root.getAsJsonObject("platforms");
        if (!platforms.has(platform) && create) {
            var value = new JsonObject(); value.addProperty("active", ""); value.add("accounts", new JsonArray());
            platforms.add(platform, value);
        }
        return platforms.has(platform) ? platforms.getAsJsonObject(platform) : null;
    }
    private static JsonObject active(JsonObject root, String platform) {
        JsonObject state = platform(root, platform, false);
        return state == null ? null : find(state, text(state, "active"));
    }
    private static JsonObject find(JsonObject state, String id) {
        for (var value : state.getAsJsonArray("accounts")) if (id.equals(text(value.getAsJsonObject(), "id"))) return value.getAsJsonObject();
        return null;
    }
    private static String text(JsonObject object, String key) {
        if (!object.has(key)) return "";
        var value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid private account field");
        return value.getAsString();
    }
    /** Strict, bounded parsing also protects legacy values and rejects silently overwritten duplicate keys. */
    private static JsonElement parse(String json) {
        if (json.length() > MAX_FILE_BYTES) throw new IllegalArgumentException("Private account storage exceeds its limit");
        rejectLegacyJsonExtensions(json);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            JsonElement value = readValue(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return value;
        } catch (IOException | RuntimeException error) {
            // Never expose parser paths or snippets, which may contain authentication material.
            throw new IllegalArgumentException("Invalid private account data");
        }
    }

    /** Gson 2.10 lacks STRICT mode and otherwise accepts raw controls and escaped apostrophes. */
    private static void rejectLegacyJsonExtensions(String json) {
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (c < 0x20) throw new IllegalArgumentException("Invalid private account data");
                if (escaped) {
                    if ("\"\\/bfnrtu".indexOf(c) < 0) throw new IllegalArgumentException("Invalid private account data");
                    escaped = false;
                } else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
        }
    }

    private static JsonElement readValue(JsonReader reader, int depth, int[] nodes) throws IOException {
        if (depth > 24 || ++nodes[0] > 100_000) throw new IOException();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (key.length() > 256 || object.has(key)) throw new IOException();
                    object.add(key, readValue(reader, depth + 1, nodes));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(readValue(reader, depth + 1, nodes));
                reader.endArray(); yield array;
            }
            case STRING -> {
                String value = reader.nextString();
                if (value.length() > 131072) throw new IOException();
                yield new JsonPrimitive(value);
            }
            case NUMBER -> {
                String number = reader.nextString();
                if (number.length() > 128) throw new IOException();
                yield new JsonPrimitive(new java.math.BigDecimal(number));
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException();
        };
    }

    private static void keys(JsonObject value, String... permitted) {
        Set<String> names = Set.of(permitted);
        if (!names.containsAll(value.keySet())) throw new IllegalArgumentException("Unknown private account field");
    }

    private static void validateBounds(JsonElement value, int depth, int[] nodes) {
        if (depth > 24 || ++nodes[0] > 100_000) throw new IllegalArgumentException("Private account data exceeds its limit");
        if (value.isJsonObject()) {
            for (var entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().length() > 256) throw new IllegalArgumentException("Invalid private account field");
                validateBounds(entry.getValue(), depth + 1, nodes);
            }
        } else if (value.isJsonArray()) {
            for (var item : value.getAsJsonArray()) validateBounds(item, depth + 1, nodes);
        } else if (value.isJsonPrimitive()) {
            var primitive = value.getAsJsonPrimitive();
            if ((primitive.isString() && primitive.getAsString().length() > 131072)
                    || (primitive.isNumber() && primitive.getAsString().length() > 128))
                throw new IllegalArgumentException("Private account data exceeds its limit");
        }
    }

    private static void validate(JsonObject root) {
        validateBounds(root, 0, new int[]{0});
        keys(root, "version", "platforms", "profile");
        if (!root.has("version") || !root.get("version").isJsonPrimitive()
                || !root.get("version").getAsJsonPrimitive().isNumber() || !root.get("version").getAsString().equals("1"))
            throw new IllegalArgumentException("Unsupported private account storage");
        var platforms = root.getAsJsonObject("platforms");
        if (platforms == null || platforms.size() > TuneWeavePlatform.values().length) throw new IllegalArgumentException("Invalid platforms");
        for (var entry : platforms.entrySet()) {
            TuneWeavePlatform.requireApiName(entry.getKey());
            var state = entry.getValue().getAsJsonObject();
            keys(state, "active", "accounts");
            var accounts = state.getAsJsonArray("accounts");
            if (accounts == null || accounts.size() > 32) throw new IllegalArgumentException("Too many saved accounts");
            Set<String> ids = new HashSet<>();
            for (var value : accounts) {
                var account = value.getAsJsonObject();
                keys(account, "id", "credential", "userId", "nickname", "avatarUrl");
                String id = text(account, "id"), credential = text(account, "credential");
                if (!id.matches("[A-Za-z0-9_-]{1,80}") || !ids.add(id) || credential.isBlank() || credential.length() > 131072
                        || credential.chars().anyMatch(c -> c < 32 || c == 127)) throw new IllegalArgumentException("Invalid saved account");
                for (String key : List.of("userId", "nickname", "avatarUrl")) if (text(account, key).length() > 8192)
                    throw new IllegalArgumentException("Invalid account profile");
            }
            if (!text(state, "active").isEmpty() && !ids.contains(text(state, "active"))) throw new IllegalArgumentException("Invalid active account");
        }
        if (root.has("profile") && !root.get("profile").isJsonNull()) validateProfile(root.get("profile"));
    }

    private static void validateProfile(JsonElement data) {
        if (!data.isJsonObject()) throw new IllegalArgumentException("Invalid local profile");
        JsonObject value = data.getAsJsonObject();
        keys(value, "profile", "idlePlaySources");
        if (value.has("profile") && !value.get("profile").isJsonNull()) {
            JsonObject profile = value.getAsJsonObject("profile");
            keys(profile, "nickname", "avatarUrl", "userId", "vipType");
            optionalText(profile, "nickname", 8192);
            optionalText(profile, "avatarUrl", 8192);
            if (profile.has("userId")) integer(profile.get("userId"));
            if (profile.has("vipType") && !profile.get("vipType").isJsonNull()) {
                JsonElement vip = profile.get("vipType");
                if (!vip.isJsonPrimitive()) throw new IllegalArgumentException("Invalid profile membership");
                if (vip.getAsJsonPrimitive().isNumber()) {
                    if (!Set.of(0L, -1L, 11L, -2L).contains(integer(vip)))
                        throw new IllegalArgumentException("Invalid profile membership");
                } else if (!vip.getAsJsonPrimitive().isString()
                        || !Set.of("NORMAL", "ALL_ACCESS", "VIP", "SVIP").contains(vip.getAsString().toUpperCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Invalid profile membership");
                }
            }
        }
        if (value.has("idlePlaySources") && !value.get("idlePlaySources").isJsonNull()) {
            JsonArray sources = value.getAsJsonArray("idlePlaySources");
            if (sources.size() > 4096) throw new IllegalArgumentException("Too many idle sources");
            for (JsonElement source : sources) {
                JsonObject item = source.getAsJsonObject();
                keys(item, "id", "type", "mode", "sourceReference");
                integer(item.get("id"));
                // JsonUtil's Class adapter loads the named class. Only the two supported models are valid here.
                if (!Set.of("indi.mopelotus.musichud.beans.music.Album", "indi.mopelotus.musichud.beans.music.Playlist")
                        .contains(text(item, "type"))) throw new IllegalArgumentException("Invalid idle source type");
                optionalText(item, "sourceReference", 512);
                String reference = item.has("sourceReference") && !item.get("sourceReference").isJsonNull()
                        ? text(item, "sourceReference") : "";
                if (!reference.isBlank() && !reference.contains(":")) throw new IllegalArgumentException("Invalid idle source reference");
                if (item.has("mode") && !item.get("mode").isJsonNull()) {
                    JsonElement mode = item.get("mode");
                    if (!mode.isJsonPrimitive()) throw new IllegalArgumentException("Invalid idle source mode");
                    if (mode.getAsJsonPrimitive().isNumber()) {
                        if (!Set.of(0L, 1L).contains(integer(mode))) throw new IllegalArgumentException("Invalid idle source mode");
                    } else if (!mode.getAsJsonPrimitive().isString()
                            || !Set.of("RANDOM", "SEQUENTIAL").contains(mode.getAsString().toUpperCase(Locale.ROOT))) {
                        throw new IllegalArgumentException("Invalid idle source mode");
                    }
                }
            }
        }
    }

    private static void optionalText(JsonObject value, String key, int limit) {
        if (value.has(key) && !value.get(key).isJsonNull() && text(value, key).length() > limit)
            throw new IllegalArgumentException("Invalid local profile field");
    }

    private static long integer(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Invalid local profile integer");
        try { return value.getAsBigDecimal().longValueExact(); }
        catch (ArithmeticException error) { throw new IllegalArgumentException("Invalid local profile integer"); }
    }
}
