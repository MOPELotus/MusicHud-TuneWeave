package indi.mopelotus.musichud.server.api.tuneweave;

import java.util.Locale;

/**
 * Route policy for the TuneWeave bridge. TuneWeave is intentionally a broad
 * provider API, so unknown future non-social routes remain usable without a
 * client release. Social resources are excluded at this single boundary.
 */
public final class TuneWeaveRoutePolicy {
    private TuneWeaveRoutePolicy() {
    }

    public static boolean isAllowed(String method, String path) {
        if (method == null || path == null || method.isBlank() || path.isBlank()) {
            return false;
        }
        String normalized = normalizePath(path);
        if (!("GET".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method)
                || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method))) {
            return false;
        }
        if (normalized.equals("/healthz")) {
            return "GET".equalsIgnoreCase(method);
        }
        if (!normalized.startsWith("/v1/")) {
            return false;
        }
        if (normalized.contains("..") || normalized.indexOf('?') >= 0 || normalized.indexOf('#') >= 0) {
            return false;
        }
        if ("GET".equalsIgnoreCase(method)
                && normalized.startsWith("/v1/users/")
                && normalized.endsWith("/playlists/created")) {
            return true;
        }
        return !isSocialPath(normalized);
    }

    public static boolean isSocialPath(String path) {
        String normalized = normalizePath(path).toLowerCase(Locale.ROOT);
        return normalized.startsWith("/v1/resources/")
                || normalized.startsWith("/v1/users/");
    }

    public static String normalizePath(String path) {
        String value = path == null ? "" : path.trim();
        if (value.isEmpty()) {
            return "";
        }
        if (!value.startsWith("/")) {
            value = "/" + value;
        }
        while (value.contains("//")) {
            value = value.replace("//", "/");
        }
        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
