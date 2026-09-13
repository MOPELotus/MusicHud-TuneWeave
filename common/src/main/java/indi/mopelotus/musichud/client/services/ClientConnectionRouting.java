package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud.ConnectStatus;

/** Integrated worlds use their local protocol service regardless of remote-fallback preferences. */
public final class ClientConnectionRouting {
    public enum Route { REMOTE, LOCAL, DROP }
    private ClientConnectionRouting() {}

    public static Route route(boolean hasPlayer, boolean remoteWorld, ConnectStatus status,
                              boolean connectRequest, boolean isolatedAllowed) {
        if (!hasPlayer) return Route.DROP;
        if (remoteWorld && (status == ConnectStatus.CONNECTED || connectRequest)) return Route.REMOTE;
        return !remoteWorld || isolatedAllowed ? Route.LOCAL : Route.DROP;
    }
}
