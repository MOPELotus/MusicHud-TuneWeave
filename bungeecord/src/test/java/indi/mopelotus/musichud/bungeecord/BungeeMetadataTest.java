package indi.mopelotus.musichud.bungeecord;

import net.md_5.bungee.api.plugin.PluginDescription;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BungeeMetadataTest {
    @Test void processedDescriptorLoadsWithActualBungeeDescriptorType() {
        var yaml = new Yaml(new Constructor(PluginDescription.class, new LoaderOptions()));
        try (var input = getClass().getResourceAsStream("/bungee.yml")) {
            assertNotNull(input);
            PluginDescription description = yaml.load(input);
            assertEquals("MusicHud-TuneWeave", description.getName());
            assertEquals(BungeeInitializer.class.getName(), description.getMain());
            assertEquals(PluginVersion.VERSION, description.getVersion());
            assertFalse(description.getVersion().contains("${"));
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }
}
