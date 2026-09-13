package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

final class TuneWeaveIdentity {
    private TuneWeaveIdentity() {
    }

    static long stableId(TuneWeavePlatform platform, String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    (platform.apiName() + ':' + Objects.requireNonNullElse(value, ""))
                            .getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String userIdFromReference(TuneWeavePlatform platform, String reference) {
        String userId = Objects.requireNonNullElse(reference, "");
        String platformPrefix = platform.apiName() + ':';
        if (userId.startsWith(platformPrefix)) {
            userId = userId.substring(platformPrefix.length());
        }
        if (userId.startsWith("user:")) {
            userId = userId.substring("user:".length());
        }
        return userId;
    }
}
