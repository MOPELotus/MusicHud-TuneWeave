package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClients;

public final class PcmAudioInput {
    private PcmAudioInput() {}

    /** Shared media transport for public artwork, retaining the same actual-DNS and redirect checks. */
    public static InputStream openPublicMedia(String identifier, Map<String, String> headers) throws IOException {
        return open(identifier, headers, true);
    }

    static InputStream open(String identifier, Map<String, String> headers, boolean publicOnly) throws IOException {
        if (!identifier.startsWith("http://") && !identifier.startsWith("https://")) {
            if (publicOnly) throw new IOException("Public audio must use HTTP");
            return Files.newInputStream(Path.of(identifier));
        }
        if (publicOnly) SharedResourceValidator.requireSafeHttpUrl(identifier);
        var builder = HttpClients.custom().disableCookieManagement().disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom().setConnectTimeout(10_000).setSocketTimeout(10_000)
                        .setConnectionRequestTimeout(10_000).setMaxRedirects(5).build());
        if (publicOnly) builder.setDnsResolver(SharedResourceValidator::resolvePublicAddresses).setRedirectStrategy(new SafeAudioRedirectStrategy());
        var client = builder.build();
        try {
            HttpGet request = new HttpGet(identifier);
            if (headers != null) headers.forEach(request::setHeader);
            var response = client.execute(request);
            if (response.getStatusLine().getStatusCode() != 200 || response.getEntity() == null) {
                response.close(); throw new IOException("Audio stream request failed");
            }
            return new FilterInputStream(response.getEntity().getContent()) {
                private boolean closed;
                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try { super.close(); } finally { try { response.close(); } finally { client.close(); } }
                }
            };
        } catch (IOException | RuntimeException error) { client.close(); throw error; }
    }
}
