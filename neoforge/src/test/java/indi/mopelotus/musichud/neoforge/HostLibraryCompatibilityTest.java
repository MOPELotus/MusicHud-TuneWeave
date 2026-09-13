package indi.mopelotus.musichud.neoforge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HostLibraryCompatibilityTest {
    @Test
    void lavaplayerJsonLinksAgainstMinecraftJackson() throws IOException {
        // This source set uses NeoForge's strict Minecraft Jackson versions.
        // Exercise both streaming/tree conversion paths to catch runtime linkage failures.
        JsonBrowser json = JsonBrowser.parse(new ByteArrayInputStream(
                "{\"title\":\"TuneWeave\",\"tracks\":[1,2]}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("TuneWeave", json.get("title").text());
        assertEquals(List.of(1, 2), json.get("tracks").as(new TypeReference<List<Integer>>() {}));
        json.put("enabled", true);
        json.get("tracks").add(3);
        assertEquals(3, json.get("tracks").index(2).asInt(-1));
        assertEquals(Boolean.TRUE, JsonBrowser.parse(json.format()).as(Map.class).get("enabled"));
        assertThrows(IOException.class, () -> JsonBrowser.parse("{\"tracks\":[}"));
    }
}
