package indi.mopelotus.musichud.client.services.music;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountModulesByPlatformTest {
    @Test void platformCachesCannotPolluteEachOtherAndInvalidateTogether() {
        var modules = new AccountModulesByPlatform<String, String, String>();
        var netease = modules.forPlatform(TuneWeavePlatform.NETEASE);
        var qq = modules.forPlatform(TuneWeavePlatform.QQ);
        assertEquals("netease", netease.albums(false, () -> "netease", Runnable::run).join());
        assertEquals("qq", qq.albums(false, () -> "qq", Runnable::run).join());
        assertEquals("netease", netease.albums(false, () -> fail("Wrong platform cache"), Runnable::run).join());
        modules.invalidate();
        assertNotSame(netease, modules.forPlatform(TuneWeavePlatform.NETEASE));
        assertEquals("new", modules.forPlatform(TuneWeavePlatform.QQ).albums(false, () -> "new", Runnable::run).join());
    }

    @Test void invalidationWithoutLoadedModulesStillClearsEntityScope() {
        Object before = MusicEntityCache.captureGeneration();
        new AccountModulesByPlatform<>().invalidate();
        assertNotSame(before, MusicEntityCache.captureGeneration());
    }
}
