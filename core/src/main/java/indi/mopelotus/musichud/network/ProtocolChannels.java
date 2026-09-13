package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.ProjectIdentity;
import java.util.Locale;

public final class ProtocolChannels {
    private ProtocolChannels() {}
    public static String id(Class<?> payload) {
        String[] words = payload.getSimpleName().split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
        return ProjectIdentity.MOD_ID + ":" + String.join("_", words).toLowerCase(Locale.ROOT);
    }
}
