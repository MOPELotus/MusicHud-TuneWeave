package indi.mopelotus.musichud.server.playback;

import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.beans.music.MusicResourceInfo;
import indi.mopelotus.musichud.beans.music.PlaybackSession;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Validates untrusted resolver output before it becomes shared public playback state. */
public final class SharedResourceValidator {
    private static final int MAX_REFERENCE_LENGTH = 512;
    private static final int MAX_TITLE_LENGTH = 500;
    private static final int MAX_HEADER_VALUE_LENGTH = 4096;
    private static final int MAX_DURATION_MILLIS = 24 * 60 * 60 * 1000;
    private static final Set<String> ALLOWED_HEADERS = Set.of(
            "accept", "accept-language", "origin", "referer", "user-agent");

    private SharedResourceValidator() {
    }

    public static PlaybackSession createSession(UUID sessionId, long sequence, int revision,
                                                MusicDetail requested, MusicDetail canonical,
                                                MusicResourceInfo resourceInfo,
                                                java.time.ZonedDateTime startTime) {
        validateCanonicalMetadata(requested, canonical);
        MusicResourceInfo sanitizedResource = sanitizeResource(canonical, resourceInfo);
        return new PlaybackSession(sessionId, sequence, revision, canonical.withPlaybackSource(requested.getPlaybackSource()), sanitizedResource, startTime);
    }

    public static MusicResourceInfo sanitizeResource(MusicDetail canonical,
                                                     MusicResourceInfo resourceInfo) {
        if (resourceInfo == null || resourceInfo == MusicResourceInfo.NONE) {
            throw new IllegalArgumentException("Resolver returned no music resource");
        }
        requireSafeHttpUrl(resourceInfo.getUrl());
        resourceInfo.getBackupUrls().forEach(SharedResourceValidator::requireSafeHttpUrl);
        if (resourceInfo.getId() != 0L && resourceInfo.getId() != canonical.getId()) {
            throw new IllegalArgumentException("Resolved resource does not match canonical track");
        }
        Map<String, String> safeHeaders = new LinkedHashMap<>();
        resourceInfo.getHeaders().forEach((name, value) -> {
            if (name == null || value == null) return;
            String normalizedName = name.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_HEADERS.contains(normalizedName)) return;
            if (value.length() > MAX_HEADER_VALUE_LENGTH || containsLineBreak(value)) {
                throw new IllegalArgumentException("Invalid shared resource header value");
            }
            safeHeaders.put(normalizedName, value);
        });
        MusicResourceInfo sanitized = new MusicResourceInfo(
                canonical.getId(), resourceInfo.getUrl(), resourceInfo.getBitrate(),
                resourceInfo.getSize(), resourceInfo.getType(), resourceInfo.getMd5(),
                resourceInfo.getFee(), resourceInfo.getTime(), safeHeaders,
                resourceInfo.getBackupUrls(), resourceInfo.getRequestedQuality(), resourceInfo.getActualQuality());
        sanitized.setResolvedTrackReference(resourceInfo.getResolvedTrackReference());
        return sanitized;
    }

    public static URI requireSafeHttpUrl(String value) {
        final URI uri;
        try {
            uri = URI.create(value == null ? "" : value.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid shared resource URL", error);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Shared resource URL must use HTTP or HTTPS");
        }
        if (uri.getUserInfo() != null || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Shared resource URL must have a safe host");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) {
            throw new IllegalArgumentException("Shared resource URL has an invalid port");
        }
        String host = uri.getHost();
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")) {
            throw new IllegalArgumentException("Localhost is not allowed for shared resources");
        }
        try {
            resolvePublicAddresses(host);
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("Shared resource host cannot be resolved", error);
        }
        return uri;
    }

    /** Resolves the exact addresses an HTTP client may connect to, closing DNS-rebinding gaps. */
    public static InetAddress[] resolvePublicAddresses(String host) throws UnknownHostException {
        if (host == null || host.isBlank()) {
            throw new UnknownHostException("Shared resource host is blank");
        }
        InetAddress[] addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) {
            throw new UnknownHostException("Shared resource host cannot be resolved");
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new UnknownHostException(
                        "Shared resource host resolves to a non-public address");
            }
        }
        return addresses;
    }

    private static void validateCanonicalMetadata(MusicDetail requested, MusicDetail canonical) {
        if (requested == null || canonical == null || canonical == MusicDetail.NONE) {
            throw new IllegalArgumentException("Resolver returned no canonical track");
        }
        String reference = canonical.getSourceRef();
        if (reference.isBlank() || reference.length() > MAX_REFERENCE_LENGTH
                || !reference.contains(":")
                || !reference.equals(requested.getSourceRef())
                || canonical.getId() != requested.getId()
                || !canonical.getSourceKind().equals(requested.getSourceKind())
                || !Set.of("track", "video", "podcast_episode", "radio_station")
                .contains(canonical.getSourceKind())
                || !canonical.getSourcePartRef().equals(requested.getSourcePartRef())
                || canonical.getSourcePartRef().length() > MAX_REFERENCE_LENGTH
                || canonical.isClientHostedUni() != requested.isClientHostedUni()
                || canonical.isCloudSource() != requested.isCloudSource()) {
            throw new IllegalArgumentException("Resolver changed the requested resource identity");
        }
        if (canonical.getName().isBlank() || canonical.getName().length() > MAX_TITLE_LENGTH
                || canonical.getDurationMillis() <= 0
                || canonical.getDurationMillis() > MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("Resolver returned invalid canonical metadata");
        }
    }

    private static boolean containsLineBreak(String value) {
        return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19))
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            return (first & 0xfe) != 0xfc;
        }
        return false;
    }
}
