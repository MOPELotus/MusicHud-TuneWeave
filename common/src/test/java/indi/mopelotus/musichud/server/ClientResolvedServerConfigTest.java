package indi.mopelotus.musichud.server;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientResolvedServerConfigTest {
    @Test void cannotStartProviderProcessOrStoreProviderConfiguration() {
        var config = new ClientResolvedServerConfig() { public void save() {} };
        assertFalse(config.getStartupBinaryApiServerWhenLaunch());
        assertThrows(UnsupportedOperationException.class, () -> config.setStartupBinaryApiServerWhenLaunch(true));
        assertThrows(UnsupportedOperationException.class, () -> config.setServerApiBaseUrl("http://private"));
        assertThrows(UnsupportedOperationException.class, () -> config.setServerApiBinaryExecutablePath("api.exe"));
        config.setPusherVoteAdditionalRate(Double.NaN); assertEquals(.5, config.getPusherVoteAdditionalRate());
        config.setPusherVoteAdditionalRate(2); assertEquals(1, config.getPusherVoteAdditionalRate());
    }
}
