package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TuneWeaveBrowserVerificationTest {
    private JsonObject instructions() {
        JsonObject value = new JsonObject();
        value.addProperty("url", "https://h5.kugou.com/verify?event=synthetic");
        value.addProperty("message_origin", "https://h5.kugou.com");
        value.addProperty("message_type", "kgVerifyCallbackData");
        value.addProperty("response_field", "dataJson");
        value.addProperty("verification_id", "synthetic"); return value;
    }
    @Test void rejectsUntrustedInstructionsBeforeOpeningAListener() {
        for (String url : new String[]{"http://h5.kugou.com/verify", "https://h5.kugou.com.evil.test/", "https://user@h5.kugou.com/", "https://h5.kugou.com:8443/"}) {
            var input = instructions(); input.addProperty("url", url);
            assertThrows(IllegalArgumentException.class, () -> new TuneWeaveBrowserVerification(input));
        }
        var input = instructions(); input.addProperty("message_type", "wrong");
        assertThrows(IllegalArgumentException.class, () -> new TuneWeaveBrowserVerification(input));
    }
    @Test void bridgeChecksFrameSourceOriginAndOneTimeCallbackAndKeepsReceiptsPrivate() throws Exception {
        try (var bridge = new TuneWeaveBrowserVerification(instructions()); var client = HttpClient.newHttpClient()) {
            var page = client.send(HttpRequest.newBuilder(bridge.uri()).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("event.source!==frame.contentWindow"));
            assertTrue(page.body().contains("event.origin!==\"https://h5.kugou.com\""));
            assertEquals("no-store", page.headers().firstValue("Cache-Control").orElseThrow());
            URI callback = URI.create(bridge.uri() + "/receipt");
            var rejected = client.send(HttpRequest.newBuilder(callback).header("Origin", "https://evil.test")
                    .POST(HttpRequest.BodyPublishers.ofString("secret")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, rejected.statusCode()); assertFalse(bridge.receipt().isDone());
            String local = "http://127.0.0.1:" + bridge.uri().getPort();
            var accepted = client.send(HttpRequest.newBuilder(callback).header("Origin", local)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"receipt\":\"original%25\"}")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(204, accepted.statusCode()); assertEquals("{\"receipt\":\"original%25\"}", bridge.receipt().get(2, TimeUnit.SECONDS));
            var duplicate = client.send(HttpRequest.newBuilder(callback).header("Origin", local)
                    .POST(HttpRequest.BodyPublishers.ofString("different")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, duplicate.statusCode()); assertFalse(bridge.toString().contains(bridge.uri().toString()));
        }
    }
    @Test void closingCancelsPendingReceipt() throws Exception {
        var bridge = new TuneWeaveBrowserVerification(instructions()); bridge.close();
        assertTrue(bridge.receipt().isCancelled());
    }
}
