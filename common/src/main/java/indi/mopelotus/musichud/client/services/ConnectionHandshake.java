package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.ProjectIdentity;
import indi.mopelotus.musichud.interfaces.IConnectionManager.ConnectionMode;
import indi.mopelotus.musichud.network.ProtocolInfo;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse;

/** Applies a handshake once, on the client executor, while retaining the rejecting server's diagnosis. */
public final class ConnectionHandshake {
    public interface Effects {
        Object connection();
        Object player();
        boolean enabled();
        boolean allowIsolated();
        void execute(Runnable action);
        void mode(ConnectionMode mode);
        void status(MusicHud.ConnectStatus status);
        void resetPlayback();
        void joinLocalPlayer();
        void restoreSession();
        void requestInitialState();
        void refreshGui();
        /** Direct physical transport, never the status-dependent local/server routing path. */
        void leaveRemoteServer();
    }

    private record Attempt(Object connection, Object player) {}
    private final Object lock;
    private final Effects effects;
    private Attempt pending;
    private Attempt retired;

    public ConnectionHandshake(Object lock, Effects effects) {
        this.lock = lock;
        this.effects = effects;
    }

    public void begin() {
        synchronized (lock) {
            retired = null;
            pending = new Attempt(effects.connection(), effects.player());
        }
    }

    public void invalidate() {
        synchronized (lock) { pending = retired = null; }
    }

    public void receive(ConnectResponse response, Object originPlayer) {
        synchronized (lock) {
            Attempt attempt = pending;
            if (!isCurrent(attempt, originPlayer)) {
                Attempt old = retired;
                if (old == null || pending != null || !sameConnection(old, originPlayer) || !response.accepted()) return;
                effects.execute(() -> {
                    synchronized (lock) {
                        if (retired != old || pending != null || !sameConnection(old, originPlayer)) return;
                        retired = null;
                        effects.leaveRemoteServer();
                    }
                });
                return;
            }
            effects.execute(() -> {
                synchronized (lock) {
                    if (!isCurrent(attempt, originPlayer)) return;
                    pending = null;
                    effects.resetPlayback();
                    if (response.accepted() && ProtocolInfo.isCompatible(response.projectId(),
                            response.serverVersion(), response.capabilities())) {
                        // The server has already joined us. Sending DisconnectMessage here would undo that join.
                        effects.mode(ConnectionMode.EXTERNAL);
                        effects.status(MusicHud.ConnectStatus.CONNECTED);
                        effects.restoreSession();
                        effects.requestInitialState();
                    } else {
                        effects.leaveRemoteServer();
                        effects.status(MusicHud.ConnectStatus.INCOMPATIBLE);
                        if (effects.allowIsolated()) startIsolated();
                        else effects.mode(ConnectionMode.DISCONNECTED);
                    }
                    effects.refreshGui();
                }
            });
        }
    }

    public void isolate() {
        synchronized (lock) {
            retire();
            effects.resetPlayback();
            startIsolated();
        }
    }

    public void timeout() {
        synchronized (lock) {
            if (!isCurrent(pending, effects.player())) return;
            retire();
            effects.resetPlayback();
            if (effects.allowIsolated()) startIsolated();
            else effects.mode(ConnectionMode.DISCONNECTED);
            effects.refreshGui();
        }
    }

    private void retire() {
        Attempt attempt = pending;
        pending = null;
        if (attempt != null && sameConnection(attempt, effects.player())) {
            retired = attempt;
            effects.leaveRemoteServer();
        }
    }

    private void startIsolated() {
        effects.mode(ConnectionMode.ISOLATED);
        effects.joinLocalPlayer();
        effects.restoreSession();
        effects.requestInitialState();
    }

    private boolean isCurrent(Attempt attempt, Object originPlayer) {
        return attempt != null && pending == attempt && effects.enabled()
                && sameConnection(attempt, originPlayer);
    }

    private boolean sameConnection(Attempt attempt, Object originPlayer) {
        return attempt.connection() != null && attempt.connection() == effects.connection()
                && attempt.player() != null && attempt.player() == originPlayer && originPlayer == effects.player();
    }

    public static String incompatibleMessageKey(ConnectionMode mode) {
        return ProjectIdentity.MOD_ID + ".text.incompatibleWithServer"
                + (mode == ConnectionMode.ISOLATED ? ".isolated" : "");
    }
}
