package indi.mopelotus.musichud.fabric;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.metadata.version.VersionPredicate;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LoaderMetadataTest {
    private static JsonObject metadata() throws Exception {
        String text = Files.readString(Path.of(System.getProperty("musichud.test.fabricMetadata")));
        assertFalse(text.contains("${"), "Processed metadata must resolve its build placeholders");
        return JsonParser.parseString(text).getAsJsonObject();
    }

    @Test
    void metadataAcceptsTheConfiguredMinecraftAndRuntimeJava() throws Exception {
        JsonObject dependencies = metadata().getAsJsonObject("depends");
        assertTrue(VersionPredicate.parse(dependencies.get("minecraft").getAsString())
                .test(Version.parse(System.getProperty("musichud.test.minecraftVersion"))));
        assertTrue(VersionPredicate.parse(dependencies.get("java").getAsString()).test(Version.parse("21")));
        assertFalse(VersionPredicate.parse(dependencies.get("java").getAsString()).test(Version.parse("17")));
    }

    @Test
    void versionSpecificMixinTargetsRejectOtherMinecraftVersions() throws Exception {
        VersionPredicate range = VersionPredicate.parse(metadata().getAsJsonObject("depends").get("minecraft").getAsString());
        for (String version : new String[]{"1.21.10", "1.21.12", "26.1", "1.21.11-pre1"}) {
            assertFalse(range.test(Version.parse(version)), "Unexpected acceptance of " + version);
        }
    }
}
