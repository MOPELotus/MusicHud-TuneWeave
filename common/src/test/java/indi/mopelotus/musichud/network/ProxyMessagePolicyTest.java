package indi.mopelotus.musichud.network;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProxyMessagePolicyTest {
    @Test void proxyOwnsClientTrafficAndDropsBackendPlaybackAuthority() {
        assertEquals(ProxyMessagePolicy.Route.HANDLE_CLIENT, ProxyMessagePolicy.route("musichud_tuneweave:connect_request", true));
        assertEquals(ProxyMessagePolicy.Route.DROP_BACKEND, ProxyMessagePolicy.route("musichud_tuneweave:switch_music_message", false));
        assertEquals(ProxyMessagePolicy.Route.DROP_BACKEND, ProxyMessagePolicy.route("musichud_tuneweave:server_payload_fragment", false));
        assertEquals(ProxyMessagePolicy.Route.FORWARD, ProxyMessagePolicy.route("other:channel", true));
        assertEquals(ProxyMessagePolicy.Route.FORWARD, ProxyMessagePolicy.route("other:channel", false));
        assertEquals(ProxyMessagePolicy.Route.FORWARD, ProxyMessagePolicy.route(null, true));
    }
}
