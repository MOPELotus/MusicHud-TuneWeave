package indi.mopelotus.musichud.client.update;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class ClientReleaseCandidatesTest {
    private static JsonObject release(String tag) {
        var release = new JsonObject();
        release.addProperty("draft", false); release.addProperty("tag_name", tag);
        release.add("assets", JsonParser.parseString("[null,{\"name\":{}},{\"name\":\"client-updates-standard.json\"}]"));
        return release;
    }
    @Test void malformedReleaseAssetsOrAnUnavailableCatalogDoNotHideAHealthyRelease() {
        var releases = new JsonArray(); releases.add(JsonNull.INSTANCE);
        releases.add(release("v1.5.0-beta.1")); releases.add(release("v1.4.0-beta.1"));
        var found = ClientReleaseCandidates.select(releases, UpdateCatalogTest.installed("standard"), uri -> {
            assertEquals("github.com", uri.getHost());
            if (uri.getPath().contains("v1.5.0")) throw new java.io.UncheckedIOException(new java.io.IOException("unavailable candidate"));
            return UpdateCatalogTest.catalog("standard");
        }).orElseThrow();
        assertEquals("1.4.0-beta.1+26.3", found.version());
    }
    @Test void totalFailureIsReportedAndCancellationDoesNotTryOtherReleases() {
        var releases = new JsonArray(); releases.add(release("v1.4.0-beta.1"));
        assertThrows(IllegalArgumentException.class, () -> ClientReleaseCandidates.select(releases,
                UpdateCatalogTest.installed("standard"), uri -> "{}"));
        releases.add(release("v1.5.0-beta.1"));
        int[] calls = {0};
        assertThrows(CancellationException.class, () -> ClientReleaseCandidates.select(releases,
                UpdateCatalogTest.installed("standard"), uri -> { calls[0]++; throw new CancellationException("retired"); }));
        assertEquals(1, calls[0]);
    }
}
