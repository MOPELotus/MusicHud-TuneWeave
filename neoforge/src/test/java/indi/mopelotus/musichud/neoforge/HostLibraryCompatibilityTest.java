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
    void lavaplayerInitializesAgainstTheMinecraftHttpClientWithoutNetworkAccess() throws Exception {
        var version = org.apache.http.util.VersionInfo.loadVersionInfo("org.apache.http.client",
                org.apache.http.client.HttpClient.class.getClassLoader());
        assertNotNull(version);
        assertEquals("4.5.13", version.getRelease());
        try (var manager = com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools.createDefaultThreadLocalManager();
             var http = manager.getInterface()) {
            assertNotNull(http.getHttpClient());
            assertNotNull(http.getContext());
            var uri = new org.apache.http.client.utils.URIBuilder().setScheme("https")
                    .setHost("example.invalid").setPath("/track").addParameter("name", "雨夜").build();
            var request = new org.apache.http.client.methods.HttpGet(uri);
            assertEquals("example.invalid", request.getURI().getHost());
            assertEquals("雨夜", new org.apache.http.client.utils.URIBuilder(request.getURI())
                    .getQueryParams().getFirst().getValue());
            assertThrows(IllegalArgumentException.class, () -> new org.apache.http.client.methods.HttpGet("https://["));
        }
    }

    @Test
    void lavaplayerJsonLinksAgainstItsOwnJackson() throws IOException {
        // Minecraft 1.21.1 does not provide Jackson; Lavaplayer supplies its own.
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
