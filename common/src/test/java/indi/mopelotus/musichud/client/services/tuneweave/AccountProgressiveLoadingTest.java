package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class AccountProgressiveLoadingTest {
    @Test void allAccountModulesPublishBeforeNextPageAndResumeOnlyMissingPage() {
        for (String module : List.of("playlists", "albums", "artists", "bilibili")) {
            var platform = module.equals("bilibili") ? TuneWeavePlatform.BILIBILI : TuneWeavePlatform.NETEASE;
            List<Integer> offsets = new ArrayList<>();
            List<Integer> shown = new ArrayList<>();
            AtomicBoolean fail = new AtomicBoolean(true);
            ClientConfig config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class<?>[]{ClientConfig.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                        case "getDefaultMusicPlatform" -> platform.apiName();
                        case "getTuneWeaveCredential" -> "test";
                        default -> throw new AssertionError(method.getName());
                    });
            var gateway = new TuneWeaveGateway(config, (b, m, p, q, body, credentials) -> {
                if (p.equals("/v1/account/favorites/playlist")) {
                    JsonObject liked = new JsonObject(); liked.addProperty("ref", "netease:liked");
                    return new TuneWeaveApiClient.TuneWeaveResponse(200, liked, new JsonObject());
                }
                int offset = Integer.parseInt(q.get("offset")); offsets.add(offset);
                if (offset == 1) {
                    assertFalse(shown.isEmpty(), "First page must be visible before requesting page two");
                    if (fail.get()) throw new IllegalStateException("offline");
                }
                return ResumableOffsetCollectionTest.page(offset == 0, offset + 1,
                        platform == TuneWeavePlatform.BILIBILI ? "bilibili:favorite:" + offset : "netease:" + offset);
            });
            Map<TuneWeavePlatform, TuneWeaveSession> sessions = new EnumMap<>(TuneWeavePlatform.class);
            sessions.put(platform, new TuneWeaveSession(platform, "1", "User", "", true));
            var mapper = new TuneWeaveEntityMapper(sessions::get);
            var service = new TuneWeaveAccountService(gateway, new TuneWeaveAuthenticationService(gateway, sessions), mapper);
            Runnable load = () -> {
                switch (module) {
                    case "albums" -> service.loadAlbums(platform, false, values -> shown.add(values.size()));
                    case "artists" -> service.loadArtists(platform, false, values -> shown.add(values.size()));
                    default -> service.loadPlaylists(platform, false, values -> shown.add(values.getCreatedPlaylist().size()));
                }
            };
            assertThrows(IllegalStateException.class, load::run);
            fail.set(false);
            load.run();
            assertEquals(List.of(0, 1, 1), offsets, module);
            assertEquals(module.equals("bilibili") ? 1 : 2, shown.getLast(), module);
            load.run();
            assertEquals(List.of(0, 1, 1), offsets, "Completed pages should remain cached");
        }
    }
}
