package indi.mopelotus.musichud.client.audio.decoder;

import indi.mopelotus.musichud.server.playback.SharedResourceValidator;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.impl.client.DefaultRedirectStrategy;
import org.apache.http.protocol.HttpContext;

import java.net.URI;

final class SafeAudioRedirectStrategy extends DefaultRedirectStrategy {
    @Override
    public URI getLocationURI(HttpRequest request, HttpResponse response,
                              HttpContext context) throws ProtocolException {
        return requireSafeLocation(super.getLocationURI(request, response, context));
    }

    static URI requireSafeLocation(URI target) throws ProtocolException {
        try {
            return SharedResourceValidator.requireSafeHttpUrl(target.toString());
        } catch (IllegalArgumentException error) {
            throw new ProtocolException("Unsafe audio redirect target", error);
        }
    }
}
