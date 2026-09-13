package indi.mopelotus.musichud.server.api;

import indi.mopelotus.musichud.platform.mod.config.ServerConfigDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StandardDistributionTest {
    @Test
    void standardEditionPreservesAcquisitionAndExistingDefaults() throws Exception {
        assertNotNull(Class.forName("indi.mopelotus.musichud.server.api.ApiServerFetcher"));
        assertNotNull(Class.forName("indi.mopelotus.musichud.server.api.ApiBinaryUpdateService"));
        var constructor = ServerConfigDefinition.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var config = constructor.newInstance();
        assertTrue(config.getStartupBinaryApiServerWhenLaunch());
        assertEquals("musichud-tuneweave/tuneweave", config.getServerApiBinaryExecutablePath());
    }
}
