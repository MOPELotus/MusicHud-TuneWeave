package indi.mopelotus.musichud.client.ui.pages;

import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.ServerConfig;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ApiConnectionSettingsTest {
    @Test void userAddressUpdatesBothTheClientGatewayAndServiceConfiguration() {
        List<String> values = new ArrayList<>();
        assertTrue(ApiConnectionSettings.apply(" http://127.0.0.1:18732/ ", recorder(ClientConfig.class, values), recorder(ServerConfig.class, values)));
        assertEquals(List.of("setTuneWeaveBaseUrl=http://127.0.0.1:18732", "setServerApiBaseUrl=http://127.0.0.1:18732"), values);
    }
    @Test void invalidAddressesLeaveBothExistingConfigurationsUntouched() {
        List<String> values = new ArrayList<>();
        for (String invalid : List.of("file:///tmp/service", "http://user:secret@localhost:7832", "http://localhost:7832/?token=secret", "http://localhost/#x", "http://[", "http://localhost:0", "http://localhost:99999", "not a URL"))
            assertFalse(ApiConnectionSettings.apply(invalid, recorder(ClientConfig.class, values), recorder(ServerConfig.class, values)));
        assertTrue(values.isEmpty());
    }
    private static <T> T recorder(Class<T> type, List<String> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            values.add(method.getName() + "=" + args[0]); return null;
        }));
    }
}
