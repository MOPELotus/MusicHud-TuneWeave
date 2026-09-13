package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtendedPaginationTest {
    static ClientConfig config(AtomicReference<String> credential) {
        return (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "netease";
                    case "getTuneWeaveCredential" -> credential.get();
                    default -> throw new AssertionError(method.getName());
                });
    }
    @Test void cloudVideoPodcastAndEpisodeProductionPathsPublishAndResumeMissingPage() {
        for (String kind : List.of("cloud", "video", "podcast", "episode")) {
            List<Integer> offsets = new ArrayList<>(), shown = new ArrayList<>();
            AtomicBoolean fail = new AtomicBoolean(true);
            var gateway = new TuneWeaveGateway(config(new AtomicReference<>("test")), (b, m, p, q, body, c) -> {
                int offset = Integer.parseInt(q.get("offset")); offsets.add(offset);
                if (offset == 1) {
                    assertFalse(shown.isEmpty(), kind + " must publish first page before requesting page two");
                    if (fail.get()) throw new IllegalStateException("offline");
                }
                return ResumableOffsetCollectionTest.page(offset == 0, offset + 1, "netease:" + offset);
            });
            var sessions = new EnumMap<TuneWeavePlatform, TuneWeaveSession>(TuneWeavePlatform.class);
            var entities = new TuneWeaveEntityMapper(sessions::get);
            var auth = new TuneWeaveAuthenticationService(gateway, sessions);
            var cloud = new TuneWeaveCloudService(gateway, auth, entities);
            var programs = new TuneWeaveProgramService(gateway, entities);
            var catalog = new TuneWeaveCatalogService(gateway, entities, new TuneWeaveAccountService(gateway, auth, entities));
            Runnable load = () -> {
                switch (kind) {
                    case "cloud" -> cloud.loadLibrary(false, value -> shown.add(value.tracks().size()));
                    case "video" -> catalog.loadVideoParts("netease:video", false, value -> shown.add(value.size()));
                    case "podcast" -> programs.loadPodcasts(TuneWeavePlatform.NETEASE, "", false, value -> shown.add(value.size()));
                    case "episode" -> programs.loadPodcastEpisodes(new TuneWeavePodcast("netease:podcast", "", "", "", "", "", "", 0, 0, 0, false), false, value -> shown.add(value.size()));
                }
            };
            assertThrows(IllegalStateException.class, load::run);
            fail.set(false); load.run();
            assertEquals(List.of(0, 1, 1), offsets, kind); assertEquals(2, shown.getLast());
            load.run(); assertEquals(List.of(0, 1, 1), offsets, "Fresh completed checkpoint must be reused");
        }
    }

    @Test void radioCursorRejectsNonAdjacentCycleAndResumesFromReturnedOpaquePosition() {
        List<String> requests = new ArrayList<>(); AtomicBoolean fail = new AtomicBoolean(true);
        var gateway = new TuneWeaveGateway(config(new AtomicReference<>("test")), (b, m, p, q, body, c) -> {
            String cursor = q.getOrDefault("last_id", "start"); requests.add(cursor);
            if (cursor.equals("A") && fail.get()) throw new IllegalStateException("offline");
            return cursorPage(true, cursor.equals("start") || cursor.equals("B") ? "A" : "B", "netease:" + cursor);
        });
        var programs = new TuneWeaveProgramService(gateway, new TuneWeaveEntityMapper(platform -> null));
        List<Integer> shown = new ArrayList<>();
        Runnable load = () -> programs.loadRadioStations(TuneWeavePlatform.NETEASE, "", "", false, values -> shown.add(values.size()));
        assertThrows(IllegalStateException.class, load::run);
        fail.set(false);
        assertThrows(IllegalArgumentException.class, load::run);
        assertEquals(List.of("start", "A", "A", "B"), requests);
        assertEquals(2, shown.getLast(), "Cyclic page must not be published");
    }

    @Test void cloudMalformedPaginationCannotClaimACompleteEmptyLibrary() {
        for (String pagination : List.of(
                "{\"has_more\":true,\"next_offset\":0,\"total\":50,\"extensions\":{}}",
                "{\"has_more\":\"true\",\"next_offset\":1}", "{\"has_more\":false,\"next_offset\":-1}")) {
            var gateway = new TuneWeaveGateway(config(new AtomicReference<>("test")), (b, m, p, q, body, c) -> {
                JsonObject meta = new JsonObject(); meta.add("pagination", JsonParser.parseString(pagination));
                return new TuneWeaveApiClient.TuneWeaveResponse(200, new JsonArray(), meta);
            });
            var cloud = new TuneWeaveCloudService(gateway, new TuneWeaveAuthenticationService(gateway, new HashMap<>()), new TuneWeaveEntityMapper(platform -> null));
            assertThrows(RuntimeException.class, cloud::loadLibrary);
        }
    }

    @Test void clearingDuringPublicationCancelsFurtherPagesAndAccountChangeRejectsResponse() {
        var credential = new AtomicReference<>("old"); AtomicInteger requests = new AtomicInteger(); AtomicBoolean switchAccount = new AtomicBoolean();
        var gateway = new TuneWeaveGateway(config(credential), (b, m, p, q, body, c) -> {
            requests.incrementAndGet(); if (switchAccount.get()) credential.set("new");
            return ResumableOffsetCollectionTest.page(true, 1, "netease:item");
        });
        var programs = new TuneWeaveProgramService(gateway, new TuneWeaveEntityMapper(platform -> null));
        assertThrows(java.util.concurrent.CancellationException.class, () -> programs.loadPodcasts(TuneWeavePlatform.NETEASE, "", false, values -> programs.clear()));
        assertEquals(1, requests.get()); switchAccount.set(true);
        assertThrows(java.util.concurrent.CancellationException.class, () -> programs.loadPodcasts(TuneWeavePlatform.NETEASE, ""));
    }

    private static TuneWeaveApiClient.TuneWeaveResponse cursorPage(boolean more, String cursor, String ref) {
        var response = ResumableOffsetCollectionTest.page(more, 0, ref);
        JsonObject next = new JsonObject(); next.addProperty("id", cursor); next.addProperty("score", "1.0");
        JsonObject extensions = new JsonObject(); extensions.add("next_cursor", next);
        response.meta().getAsJsonObject("pagination").add("extensions", extensions);
        return response;
    }
}
