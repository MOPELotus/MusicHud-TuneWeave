package indi.mopelotus.musichud.server.api.tuneweave;

import java.util.Locale;

public enum TuneWeavePlatform {
    NETEASE("netease"),
    QQ("qq"),
    BILIBILI("bilibili"),
    SODA("soda"),
    KUGOU("kugou"),
    KUWO("kuwo"),
    MIGU("migu");

    private final String apiName;

    TuneWeavePlatform(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }

    public static TuneWeavePlatform fromApiName(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "qq", "tencent" -> QQ;
            case "bilibili", "bili" -> BILIBILI;
            case "soda" -> SODA;
            case "kugou" -> KUGOU;
            case "kuwo" -> KUWO;
            case "migu" -> MIGU;
            default -> NETEASE;
        };
    }

    /** Strict boundary parsing; unknown providers must never borrow NetEase credentials. */
    public static TuneWeavePlatform requireApiName(String value) {
        for (TuneWeavePlatform platform : values()) {
            if (platform.apiName.equals(value)) return platform;
        }
        throw new IllegalArgumentException("Unknown TuneWeave platform");
    }
}
