package indi.mopelotus.musichud.network;

import indi.mopelotus.musichud.ProjectIdentity;

/** A proxy owns its namespace and never forwards backend playback authority to clients. */
public final class ProxyMessagePolicy {
    public enum Route { FORWARD, HANDLE_CLIENT, DROP_BACKEND }
    private ProxyMessagePolicy() {}
    public static Route route(String channel, boolean fromClient) {
        if (channel == null || !channel.startsWith(ProjectIdentity.MOD_ID + ":")) return Route.FORWARD;
        return fromClient ? Route.HANDLE_CLIENT : Route.DROP_BACKEND;
    }
}
