package indi.mopelotus.musichud.server.api;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadProxyTest {
    @Test
    void axisNowRoutesReleaseAndChecksumRequestsWithoutLosingTheirPathsOrQueries() {
        for (String file : new String[] {"tuneweave-linux-x86_64", "tuneweave-linux-x86_64.sha256"}) {
            URI original = URI.create("https://github.com/MOPELotus/TuneWeave/releases/download/v1/"
                    + file + "?download=1");
            URI request = HttpRequest.newBuilder(URI.create(ApiServerFetcher.DownloadProxy.AXIS_NOW_IPV4
                    .resolveUrl(original.toString()))).GET().build().uri();
            assertEquals("https", request.getScheme());
            assertEquals("axisnow.gh-proxy.org", request.getHost());
            assertEquals("/https://" + original.getHost() + original.getRawPath(), request.getRawPath());
            assertEquals(original.getRawQuery(), request.getRawQuery());
            assertEquals(original.toString(), ApiServerFetcher.DownloadProxy.DIRECT.resolveUrl(original.toString()));
        }
    }
}
