package indi.mopelotus.musichud.server.api;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServiceArtifactIsolationTest {
    @Test void malformedForeignAndIncompleteMatchingArtifactsDoNotHideTheHealthyArtifact() throws Exception {
        var root = JsonParser.parseString("{\"schema_version\":1,\"project\":\"TuneWeave\",\"api_version\":\"v1\",\"artifacts\":[]}").getAsJsonObject();
        var rows = root.getAsJsonArray("artifacts");
        rows.add(JsonNull.INSTANCE); rows.add(new JsonPrimitive("malformed"));
        for (String platform : new String[]{"linux", "windows", "macos"}) {
            for (String arch : new String[]{"x86_64", "aarch64"}) {
                var artifact = new JsonObject();
                artifact.addProperty("platform", platform); artifact.addProperty("architecture", arch);
                rows.add(artifact.deepCopy()); // Matching but incomplete entries must not win selection.
                artifact.addProperty("file", "healthy");
                artifact.addProperty("download_url", "https://example.com/healthy");
                artifact.add("verification", JsonParser.parseString("{\"algorithm\":\"sha256\",\"checksum_url\":\"https://example.com/healthy.sha256\"}"));
                rows.add(artifact);
            }
        }
        var manifest = ApiServerFetcher.parseManifest(root.toString());
        var selected = ApiServerFetcher.currentTuneWeaveArtifact(manifest);
        assertEquals("healthy", selected.getFile());
        // Two usable declarations for the same platform remain ambiguous.
        rows.add(indi.mopelotus.musichud.utils.JsonUtil.gson.toJsonTree(selected));
        var duplicate = ApiServerFetcher.parseManifest(root.toString());
        assertThrows(IllegalStateException.class, () -> ApiServerFetcher.currentTuneWeaveArtifact(duplicate));
    }
}
