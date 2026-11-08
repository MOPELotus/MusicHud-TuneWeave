package indi.mopelotus.musichud.client.update;

import com.google.gson.*;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.utils.ReleaseVersion;
import indi.mopelotus.musichud.utils.collections.ItemMapping;
import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** One unusable GitHub release must not hide other independently verified update offers. */
final class ClientReleaseCandidates {
    private ClientReleaseCandidates() {}

    static Optional<ClientUpdateCatalog.Offer> select(JsonArray releases, ClientUpdateCatalog.Installed installed,
                                                     Function<URI, String> readCatalog) {
        ClientUpdateCatalog.Offer best = null;
        RuntimeException firstFailure = null;
        for (JsonElement element : releases) {
            try {
                var release = element.getAsJsonObject();
                var draft = release.get("draft");
                if (draft == null || !draft.isJsonPrimitive() || !draft.getAsJsonPrimitive().isBoolean())
                    throw new IllegalArgumentException("Invalid release draft flag");
                if (draft.getAsBoolean()) continue;
                String tag = ClientUpdateCatalog.string(release, "tag_name");
                try { ClientUpdateCatalog.assetUri(tag, "catalog.json"); }
                catch (IllegalArgumentException unsupportedTag) { continue; }
                ReleaseVersion target = ReleaseVersion.parse(tag.substring(1)), current = ReleaseVersion.parse(installed.version());
                if (target.compareTo(current.withoutCfRevision()) < 0) continue;
                String catalogName = "client-updates-" + installed.distribution() + ".json";
                var assetNames = ItemMapping.map("release asset names", release.getAsJsonArray("assets"),
                        asset -> ClientUpdateCatalog.string(asset.getAsJsonObject(), "name"));
                if (!assetNames.contains(catalogName)) continue;
                var body = release.get("body");
                String notes = body != null && body.isJsonPrimitive() && body.getAsJsonPrimitive().isString() ? body.getAsString() : "";
                var offer = ClientUpdateCatalog.select(readCatalog.apply(ClientUpdateCatalog.assetUri(tag, catalogName)),
                        tag, installed, true, notes);
                if (offer.isPresent() && (best == null || ReleaseVersion.parse(offer.get().version())
                        .compareTo(ReleaseVersion.parse(best.version())) > 0)) best = offer.get();
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (RuntimeException invalid) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Update check interrupted");
                if (firstFailure == null) firstFailure = invalid;
                MusicHud.getLogger(ClientReleaseCandidates.class).warn("Skipped an unusable update release candidate");
            }
        }
        if (best == null && firstFailure != null) throw firstFailure;
        return Optional.ofNullable(best);
    }
}
