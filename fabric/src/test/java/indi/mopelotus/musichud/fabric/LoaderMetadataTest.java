package indi.mopelotus.musichud.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoaderMetadataTest {
    private static VersionPredicate dependency(String id) throws Exception {
        String content = Files.readString(Path.of(System.getProperty("musichud.test.fabricMetadata")));
        assertFalse(content.contains("${"), "Unresolved metadata placeholder");
        JsonObject metadata = JsonParser.parseString(content).getAsJsonObject();
        assertEquals("musichud_tuneweave", metadata.get("id").getAsString());
        String expression = metadata.getAsJsonObject("depends").get(id).getAsString();
        assertFalse(expression.isBlank());
        return VersionPredicate.parse(expression);
    }

    @Test
    void processedMetadataAcceptsTheConfiguredMinecraftAndJava21() throws Exception {
        assertTrue(dependency("minecraft").test(Version.parse(System.getProperty("musichud.test.minecraftVersion"))));
        assertTrue(dependency("java").test(Version.parse("21")));
        assertFalse(dependency("java").test(Version.parse("20")));
    }

    @Test
    void versionBoundariesMatchTheOfficialModernUiRelease() throws Exception {
        for (String version : List.of("1.21.1")) {
            assertTrue(dependency("minecraft").test(Version.parse(version)));
        }
        for (String version : List.of("1.21", "1.21.2", "26.2")) {
            assertFalse(dependency("minecraft").test(Version.parse(version)));
        }
        assertTrue(dependency("modernui").test(Version.parse("3.13.0.1")));
        assertFalse(dependency("modernui").test(Version.parse("3.13.0.0")));
        assertFalse(dependency("modernui").test(Version.parse("3.13.0.1-beta")));
    }
}
