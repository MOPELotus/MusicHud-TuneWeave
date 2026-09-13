package indi.mopelotus.musichud.beans.music;

import java.util.Locale;
import java.util.UUID;

/** Client-owned listening-history policy; never transmitted in the Minecraft protocol. */
public enum ScrobbleMode {
    NONE, ONLY_SELF, ALL;

    public boolean allows(UUID requester, UUID localPlayer, long playedMillis) {
        return playedMillis >= 30_000 && (this == ALL
                || (this == ONLY_SELF && localPlayer != null && localPlayer.equals(requester)));
    }

    public static ScrobbleMode parse(String value) {
        if (value == null) return NONE;
        try { return valueOf(value.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException error) { return NONE; }
    }
}
