package indi.mopelotus.musichud.server.api;

import com.google.gson.JsonParser;
import indi.mopelotus.musichud.MusicHud;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DownloadProxyResourcesTest {
    @Test
    void processedTranslationsFollowTheActualDistributionAndAvailableProxyChoices() throws Exception {
        boolean cf = "cf".equals(System.getProperty("musichud.test.distribution", "standard"));
        String enumName = "indi.mopelotus.musichud.server.api.ApiServerFetcher$DownloadProxy";
        Object[] choices = cf ? new Object[0] : Class.forName(enumName).getEnumConstants();
        if (cf) assertThrows(ClassNotFoundException.class, () -> Class.forName(enumName));
        for (String locale : new String[] {"en_us", "zh_cn", "zh_hk", "zh_tw"}) {
            String prefix = MusicHud.MOD_ID + ".modal.downloadApiServer.proxy.";
            try (var input = getClass().getResourceAsStream("/assets/" + MusicHud.MOD_ID + "/lang/" + locale + ".json")) {
                assertNotNull(input, locale);
                var translations = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (cf) {
                    assertTrue(translations.keySet().stream().noneMatch(key -> key.startsWith(prefix)), locale);
                } else {
                    for (Object choice : choices) {
                        String key = prefix + ((Enum<?>) choice).name();
                        assertTrue(translations.has(key), locale + ": " + key);
                        assertFalse(translations.get(key).getAsString().isBlank(), locale + ": " + key);
                    }
                    assertTrue(translations.get(prefix + "AXIS_NOW_IPV4").getAsString().contains("Axis Now"), locale);
                }
            }
        }
    }
}
