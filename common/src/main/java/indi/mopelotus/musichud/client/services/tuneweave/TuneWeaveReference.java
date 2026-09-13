package indi.mopelotus.musichud.client.services.tuneweave;

import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;

final class TuneWeaveReference {
    private TuneWeaveReference() {
    }

    static TuneWeavePlatform requirePlatform(String value) {
        return switch (value == null ? "" : value) {
            case "netease" -> TuneWeavePlatform.NETEASE;
            case "qq" -> TuneWeavePlatform.QQ;
            case "bilibili" -> TuneWeavePlatform.BILIBILI;
            default -> throw new indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException("Unknown response platform", false);
        };
    }

    static void require(String reference, String kind) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("TuneWeave " + kind + " reference is missing");
        }
    }

    static TuneWeavePlatform platform(String reference) {
        require(reference, "resource");
        if (isBilibiliFavoritesPlaceholder(reference)) return TuneWeavePlatform.BILIBILI;
        if (reference.startsWith("account:favorite_tracks:")) return favoritePlatform(reference);
        int separator = reference.indexOf(':');
        if (separator <= 0) {
            throw new IllegalArgumentException("Invalid TuneWeave reference: " + reference);
        }
        return TuneWeavePlatform.fromApiName(reference.substring(0, separator));
    }

    static TuneWeavePlatform platformOrDefault(String reference, TuneWeavePlatform fallback) {
        require(reference, "resource");
        if (isBilibiliFavoritesPlaceholder(reference)) return TuneWeavePlatform.BILIBILI;
        if (reference.startsWith("account:favorite_tracks:")) {
            return favoritePlatform(reference);
        }
        int separator = reference.indexOf(':');
        return separator > 0
                ? TuneWeavePlatform.fromApiName(reference.substring(0, separator))
                : fallback;
    }

    static boolean isBilibiliFavoritesPlaceholder(String reference) {
        return "account:favorites:bilibili".equals(reference);
    }

    private static TuneWeavePlatform favoritePlatform(String reference) {
        return switch (reference.substring("account:favorite_tracks:".length())) {
            case "netease" -> TuneWeavePlatform.NETEASE;
            case "qq" -> TuneWeavePlatform.QQ;
            case "bilibili" -> TuneWeavePlatform.BILIBILI;
            default -> throw new IllegalArgumentException("Invalid account favorite reference");
        };
    }

    static String id(String reference) {
        require(reference, "resource");
        int separator = reference.indexOf(':');
        return separator >= 0 ? reference.substring(separator + 1) : reference;
    }

    static boolean isStyledRadio(String reference) {
        return reference != null && reference.contains(":difm:");
    }
}
