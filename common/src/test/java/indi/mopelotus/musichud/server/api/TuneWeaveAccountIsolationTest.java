package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.server.api.impl.tuneweave.TuneWeaveMusicApiService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TuneWeaveAccountIsolationTest {
    @Test
    void accountKeyIsStableAndDistinctPerMinecraftPlayer() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");

        assertEquals("minecraft-00000000000000000000000000000001",
                TuneWeaveMusicApiService.playerAccount(first));
        assertNotEquals(TuneWeaveMusicApiService.playerAccount(first),
                TuneWeaveMusicApiService.playerAccount(second));
        assertEquals("default", TuneWeaveMusicApiService.playerAccount(null));
    }
}
