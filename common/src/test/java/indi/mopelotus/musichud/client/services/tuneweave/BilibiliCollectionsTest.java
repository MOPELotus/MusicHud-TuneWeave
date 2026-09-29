package indi.mopelotus.musichud.client.services.tuneweave;
import com.google.gson.*;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.server.api.tuneweave.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BilibiliCollectionsTest {
    @Test void preservesCreatedSeasonsAndSeriesAndLoadsCollectedFoldersAndSeasons() {
        var calls = new ArrayList<String>();
        var config = (ClientConfig) Proxy.newProxyInstance(ClientConfig.class.getClassLoader(), new Class[]{ClientConfig.class},
                (p,m,a) -> switch (m.getName()) {
                    case "getTuneWeaveBaseUrl" -> "http://127.0.0.1:7832";
                    case "getDefaultMusicPlatform" -> "bilibili";
                    case "getTuneWeaveCredential" -> "synthetic";
                    default -> throw new AssertionError(m.getName());
                });
        var gateway = new TuneWeaveGateway(config, (base,method,path,query,body,credentials) -> {
            calls.add(path);
            if (path.endsWith("/playlists/created")) return ResumableOffsetCollectionTest.page(false,3,
                    "bilibili:favorite:1", "bilibili:season:2", "bilibili:series:3");
            assertTrue(path.endsWith("/favorites/playlists"));
            return ResumableOffsetCollectionTest.page(false,2,"bilibili:favorite:4","bilibili:season:5");
        });
        var sessions = new EnumMap<TuneWeavePlatform,TuneWeaveSession>(TuneWeavePlatform.class);
        sessions.put(TuneWeavePlatform.BILIBILI,new TuneWeaveSession(TuneWeavePlatform.BILIBILI,"123","User","",true));
        var service = new TuneWeaveAccountService(gateway,new TuneWeaveAuthenticationService(gateway,sessions),new TuneWeaveEntityMapper(sessions::get));
        var result = service.loadPlaylists(TuneWeavePlatform.BILIBILI);
        assertEquals("bilibili:favorite:1",result.getLikeList().getSourceRef());
        assertEquals(List.of("bilibili:season:2","bilibili:series:3"),result.getCreatedPlaylist().stream().map(x->x.getSourceRef()).toList());
        assertEquals(List.of("bilibili:favorite:4","bilibili:season:5"),result.getSubscribedPlaylist().stream().map(x->x.getSourceRef()).toList());
        assertEquals(List.of("/v1/users/bilibili%3A123/playlists/created","/v1/users/bilibili%3A123/favorites/playlists"),calls);
    }
    @Test void bridgeAllowsCollectionReadsWhileKeepingSocialAndMalformedRoutesBlocked() {
        for (String path : List.of("/v1/users/bilibili%3A123/playlists/created", "/v1/users/bilibili%3A123/favorites/playlists")) {
            assertTrue(TuneWeaveRoutePolicy.isAllowed("GET",path));
            for (String method : List.of("POST","PUT","DELETE","PATCH")) assertFalse(TuneWeaveRoutePolicy.isAllowed(method,path));
        }
        for (String path : List.of("/v1/users/bilibili%3A123/followers", "/v1/users/bilibili%3A123/favorites/playlists/extra",
                "/v1/users/a/b/favorites/playlists", "/v1/users/../favorites/playlists", "/v1/users/a/favorites/playlists?x=1",
                "/v1/users/a/favorites/playlists#x")) assertFalse(TuneWeaveRoutePolicy.isAllowed("GET",path));
    }

}
