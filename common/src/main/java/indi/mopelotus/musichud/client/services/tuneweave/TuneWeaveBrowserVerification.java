package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Ephemeral loopback bridge. The official iframe completes verification; only its original receipt returns. */
public final class TuneWeaveBrowserVerification implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CompletableFuture<String> receipt = new CompletableFuture<>();
    private final URI uri;
    private final String verificationId;
    public URI uri() { return uri; }
    public void openBrowser() throws IOException {
        if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
            java.awt.Desktop.getDesktop().browse(uri); return;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> command = os.contains("win") ? java.util.List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString())
                : os.contains("mac") ? java.util.List.of("open", uri.toString()) : java.util.List.of("xdg-open", uri.toString());
        new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
    public String verificationId() { return verificationId; }
    public CompletableFuture<String> receipt() { return receipt; }
    public TuneWeaveBrowserVerification(JsonObject verification) throws IOException {
        String url = TuneWeaveJson.requiredString(verification, "url");
        String origin = TuneWeaveJson.requiredString(verification, "message_origin");
        String type = TuneWeaveJson.requiredString(verification, "message_type");
        String field = TuneWeaveJson.requiredString(verification, "response_field");
        verificationId = TuneWeaveJson.requiredString(verification, "verification_id");
        URI target = URI.create(url);
        if (url.length() > 16384 || !"https".equals(target.getScheme()) || !"h5.kugou.com".equals(target.getHost())
                || target.getRawUserInfo() != null || target.getPort() != -1 || !"https://h5.kugou.com".equals(origin)
                || !"kgVerifyCallbackData".equals(type) || !"dataJson".equals(field)
                || verificationId.isBlank() || verificationId.length() > 512)
            throw new IllegalArgumentException("Unsupported browser verification source");
        String secret = UUID.randomUUID().toString();
        String path = "/" + secret;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
        String localOrigin = "http://127.0.0.1:" + server.getAddress().getPort();
        Gson gson = new Gson(); // HTML-safe string escaping is deliberate.
        String html = "<!doctype html><meta charset=utf-8><meta name=referrer content=no-referrer>"
                + "<title>TuneWeave verification</title><p>Complete verification below, then return to Minecraft.</p>"
                + "<iframe id=verify title=Verification width=600 height=600></iframe><p id=status></p>"
                + "<script nonce='" + secret + "'>const frame=document.getElementById('verify');frame.src=" + gson.toJson(url) + ";"
                + "let done=false;addEventListener('message',async event=>{"
                + "if(done||event.origin!==" + gson.toJson(origin) + "||event.source!==frame.contentWindow"
                + "||!event.data||event.data.type!==" + gson.toJson(type) + ")return;"
                + "const response=event.data[" + gson.toJson(field) + "];if(typeof response!=='string'||response.length>131072)return;"
                + "done=true;try{const result=await fetch(" + gson.toJson(path + "/receipt")
                + ",{method:'POST',headers:{'Content-Type':'text/plain'},body:response});"
                + "document.getElementById('status').textContent=result.ok?'Return to Minecraft.':'Verification expired.';frame.remove();"
                + "}catch(e){document.getElementById('status').textContent='Verification expired.';}});</script>";
        byte[] page = html.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'none'; frame-src https://h5.kugou.com; script-src 'nonce-" + secret + "'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
                String requestPath = exchange.getRequestURI().toString();
                if (exchange.getRequestMethod().equals("GET") && requestPath.equals(path)) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(200, page.length); exchange.getResponseBody().write(page);
                } else if (exchange.getRequestMethod().equals("POST") && requestPath.equals(path + "/receipt")
                        && localOrigin.equals(exchange.getRequestHeaders().getFirst("Origin")) && !receipt.isDone()) {
                    byte[] body = exchange.getRequestBody().readNBytes(131073);
                    if (body.length == 0 || body.length > 131072) { exchange.sendResponseHeaders(413, -1); return; }
                    String response = new String(body, StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(204, -1);
                    receipt.complete(response);
                } else exchange.sendResponseHeaders(404, -1);
            }
        });
        server.setExecutor(executor); server.start();
        receipt.orTimeout(5, TimeUnit.MINUTES).whenComplete((value, failure) -> {
            if (failure instanceof java.util.concurrent.TimeoutException) close();
        });
    }
    @Override public void close() {
        receipt.cancel(false); server.stop(0); executor.shutdownNow();
    }
    @Override public String toString() { return "TuneWeaveBrowserVerification"; }
}
