package indi.mopelotus.musichud.server;

import indi.mopelotus.musichud.beans.music.PusherInfo;

import java.util.UUID;

/** Access policy for player-owned idle playback sources. */
public final class IdleSourceAccessPolicy {
    private IdleSourceAccessPolicy() {
    }

    public static boolean canViewPrivateSource(PusherInfo owner, UUID viewerId) {
        return owner != null && viewerId != null && viewerId.equals(owner.getPlayerUUID());
    }
}
