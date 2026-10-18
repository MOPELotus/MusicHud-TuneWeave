package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud.ConnectStatus;
import indi.mopelotus.musichud.interfaces.IConnectionManager.ConnectionMode;

/** Integrated worlds use their local protocol service regardless of remote-fallback preferences. */
public final class ClientConnectionRouting {
    public enum Route { REMOTE, LOCAL, DROP }
    private ClientConnectionRouting() {}

    /** Local protocol membership follows the accepted route, not the isolated-mode label alone. */
    public static boolean usesLocalMembership(boolean integrated, ConnectionMode mode, ConnectStatus status) {
        return mode == ConnectionMode.ISOLATED
                || integrated && mode == ConnectionMode.EXTERNAL && status == ConnectStatus.CONNECTED;
    }

    public static Route route(boolean hasPlayer, boolean remoteWorld, ConnectStatus status,
                              boolean connectRequest, boolean isolatedAllowed) {
        if (!hasPlayer) return Route.DROP;
        if (remoteWorld && (status == ConnectStatus.CONNECTED || connectRequest)) return Route.REMOTE;
        return !remoteWorld || isolatedAllowed ? Route.LOCAL : Route.DROP;
    }
}
