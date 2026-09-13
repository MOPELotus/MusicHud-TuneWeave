package indi.mopelotus.musichud.server.api.tuneweave;

import java.util.Locale;

public enum TuneWeavePlatform {
    NETEASE("netease"),
    QQ("qq"),
    BILIBILI("bilibili");

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
            default -> NETEASE;
        };
    }
}
