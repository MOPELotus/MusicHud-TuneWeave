package indi.mopelotus.musichud.client.services;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.mc.UIManager;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.audio.NowPlayingInfo;
import indi.mopelotus.musichud.client.audio.StreamAudioPlayer;
import indi.mopelotus.musichud.client.network.vanilla.VanillaPlayerProxy;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.interfaces.ClientConfig;
import indi.mopelotus.musichud.interfaces.IClientLoginService;
import indi.mopelotus.musichud.interfaces.IConnectionManager;
import indi.mopelotus.musichud.network.IClientNetworkService;
import indi.mopelotus.musichud.network.ProtocolInfo;
import indi.mopelotus.musichud.network.RequestResponseManager;
import indi.mopelotus.musichud.network.payloads.pushMessages.c2s.DisconnectMessage;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectRequest;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.GetInitialStateRequest;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.GetInitialStateResponse;
import indi.mopelotus.musichud.server.ServerPlayerRegistry;
import indi.mopelotus.musichud.utils.IClientDistUtil;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Centralized connection mode control.
 * Previously the connected/isolated switching logic was scattered across LoginService,
 * MusicService and the ConnectResponse receiver, which repeatedly caused bugs.
 */
@SuppressWarnings("unused")
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class ConnectionManager implements IConnectionManager {
    private static final Logger logger = MusicHud.getLogger(ConnectionManager.class);
    private static final IClientNetworkService clientNetworkService = IClientNetworkService.getInstance();
    private static final ClientConfig clientConfig = ClientConfig.getInstance();
    private static final long TOGGLE_DEBOUNCE_DELAY_MILLIS = 300;
    // Network handshakes, especially LAN hosts during world startup, can exceed one second.
    // The generation guard still cancels this fallback after a real response or disconnect.
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static volatile ConnectionManager instance;
    private final ConnectionActionGate connectionActions = new ConnectionActionGate(this);
    private final java.util.concurrent.atomic.AtomicInteger connectGeneration = new java.util.concurrent.atomic.AtomicInteger(0);
    private final ConnectionHandshake handshake = new ConnectionHandshake(this, new ConnectionHandshake.Effects() {
        public Object connection() { return Minecraft.getInstance().getConnection(); }
        public Object player() { return Minecraft.getInstance().player; }
        public boolean enabled() { return clientConfig.getEnable(); }
        public boolean allowIsolated() { return clientConfig.getEnableIsolatedMode(); }
        public void execute(Runnable action) { Minecraft.getInstance().execute(action); }
        public void mode(ConnectionMode next) {
            connectionActions.invalidate();
            mode = next;
            connectGeneration.incrementAndGet();
            indi.mopelotus.musichud.network.PayloadFragments.resetClient();
        }
        public void status(MusicHud.ConnectStatus next) { MusicHud.setConnectStatus(next); }
        public void resetPlayback() { ConnectionManager.this.resetPlayback(); }
        public void joinLocalPlayer() {
            var player = Minecraft.getInstance().player;
            if (player != null) ServerPlayerRegistry.getInstance().join(VanillaPlayerProxy.ofPlayer(player));
        }
        public void restoreSession() { IClientLoginService.getInstance().restoreSession(); }
        public void requestInitialState() { ConnectionManager.this.requestInitialState(); }
        public void refreshGui() { IClientDistUtil.getInstance().refreshMainGUI(); }
        public void leaveRemoteServer() { ConnectionManager.this.leaveRemoteServer(); }
    });
    private final ClientPayloadAdmission clientPackets = new ClientPayloadAdmission(this, connectGeneration::get,
            () -> Minecraft.getInstance().getConnection(), () -> Minecraft.getInstance().player,
            () -> this.mode, MusicHud::getConnectStatus, () -> Minecraft.getInstance().getCurrentServer() == null,
            clientConfig::getEnable);
    private final ClientConnectionLifecycle lifecycle = new ClientConnectionLifecycle(this,
            () -> Minecraft.getInstance().getConnection(),
            () -> Minecraft.getInstance().getConnection() == null ? null : Minecraft.getInstance().getConnection().getConnection(),
            () -> Minecraft.getInstance().player, action -> Minecraft.getInstance().execute(action));

    public void onPlayerJoin(net.minecraft.world.entity.player.Player player, Runnable connect) {
        lifecycle.join(player, connect, this::continueWithPlayer);
    }

    public void onPlayerQuit(net.minecraft.world.entity.player.Player player) {
        Object physical = player instanceof net.minecraft.client.player.LocalPlayer local ? local.connection : null;
        lifecycle.quit(player, physical, this::detachPlayer, this::resetPlayback);
    }

    private void detachPlayer(Object previous) {
        ServerPlayerRegistry.getInstance().disconnect(VanillaPlayerProxy.ofPlayer((net.minecraft.world.entity.player.Player) previous));
        connectionActions.invalidate();
        handshake.invalidate();
        connectGeneration.incrementAndGet();
        mode = ConnectionMode.DISCONNECTED;
        MusicHud.setConnectStatus(MusicHud.ConnectStatus.NOT_CONNECTED);
        indi.mopelotus.musichud.network.PayloadFragments.resetClient();
    }

    public void onClientTick() {
        lifecycle.disconnectIfClosed(physical -> ((net.minecraft.network.Connection) physical).isConnected(),
                this::detachPlayer, this::resetPlayback);
        lifecycle.tick(this::continueWithPlayer);
    }

    /** A new PLAY listener on the same TCP connection keeps the chosen mode and public session. */
    private void continueWithPlayer(Object previous) {
        connectionActions.invalidate();
        handshake.invalidate();
        connectGeneration.incrementAndGet();
        indi.mopelotus.musichud.network.PayloadFragments.resetClient();
        var registry = ServerPlayerRegistry.getInstance();
        if (mode == ConnectionMode.ISOLATED) {
            // Replace atomically before leaving the old wrapper; do not temporarily retire the last member.
            registry.join(VanillaPlayerProxy.ofPlayer(Minecraft.getInstance().player));
        }
        registry.leave(VanillaPlayerProxy.ofPlayer((net.minecraft.world.entity.player.Player) previous));
        if (mode == ConnectionMode.EXTERNAL && MusicHud.getConnectStatus() != MusicHud.ConnectStatus.CONNECTED) {
            connectToExternalServer();
        } else if (mode == ConnectionMode.EXTERNAL || mode == ConnectionMode.ISOLATED) {
            // Re-handshaking an already accepted connection would reset the live playback engine.
            requestInitialState();
        }
    }

    private double lastPressTime;
    @Getter
    private volatile ConnectionMode mode = ConnectionMode.DISCONNECTED;

    public static ConnectionManager getInstance() {
        if (instance == null) {
            synchronized (ConnectionManager.class) {
                if (instance == null) {
                    instance = new ConnectionManager();
                }
            }
        }
        return instance;
    }

    @Override
    public synchronized void connectToExternalServer() {
        connectionActions.invalidate();
        handshake.invalidate();
        leaveLocalPlayer();
        indi.mopelotus.musichud.network.PayloadFragments.resetClient();
        if (clientConfig.getEnable()) {
            mode = ConnectionMode.EXTERNAL;
            MusicHud.setConnectStatus(MusicHud.ConnectStatus.NOT_CONNECTED);
            handshake.begin();
            scheduleConnectTimeoutFallback();
            clientNetworkService.sendToServer(ConnectRequest.current());
        }
    }

    private void scheduleConnectTimeoutFallback() {
        int generation = connectGeneration.incrementAndGet();
        var minecraft = Minecraft.getInstance();
        Object connection = minecraft.getConnection();
        java.util.concurrent.CompletableFuture.delayedExecutor(CONNECT_TIMEOUT.toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS, minecraft).execute(() -> {
            synchronized (ConnectionManager.this) {
                if (connection != null && connection == minecraft.getConnection() && minecraft.player != null
                        && generation == connectGeneration.get() && mode == ConnectionMode.EXTERNAL
                        && MusicHud.getConnectStatus() == MusicHud.ConnectStatus.NOT_CONNECTED) {
                    if (clientConfig.getEnableIsolatedMode()) {
                        logger.warn("No ConnectResponse within {}, falling back to isolated mode", CONNECT_TIMEOUT);
                    } else logger.warn("No ConnectResponse within {}; isolated mode is disabled", CONNECT_TIMEOUT);
                    handshake.timeout();
                }
            }
        });
    }

    @Override
    public synchronized void launchIsolated() {
        connectionActions.invalidate();
        handshake.isolate();
    }

    private void leaveLocalPlayer() {
        var player = Minecraft.getInstance().player;
        if (player != null) ServerPlayerRegistry.getInstance().leave(VanillaPlayerProxy.ofPlayer(player));
    }

    /** Capture at the transport boundary, before local dispatch is queued. */
    public indi.mopelotus.musichud.network.ClientPacketContext.Admission captureClientPayload(
            boolean remote, indi.mopelotus.musichud.network.IPlayerClient origin,
            indi.mopelotus.musichud.network.payloads.IPayload payload) {
        Object player = origin instanceof VanillaPlayerProxy proxy ? proxy.getPlayer() : null;
        return clientPackets.capture(remote, player, payload instanceof ConnectResponse);
    }

    private void leaveRemoteServer() {
        var minecraft = Minecraft.getInstance();
        if (minecraft.getCurrentServer() == null || minecraft.getConnection() == null) return;
        try {
            ((indi.mopelotus.musichud.client.network.vanilla.VanillaClientNetworkService) clientNetworkService)
                    .sendToNetworkServer(DisconnectMessage.INSTANCE);
        } catch (RuntimeException e) {
            // A server without the channel, or an already closed transport, must not prevent local fallback.
            logger.debug("Could not retire remote TuneWeave membership", e);
        }
    }

    private void resetPlayback() {
        MusicService.resetCurrentMusicStatus();
        NowPlayingInfo.getInstance().stop();
        StreamAudioPlayer.getInstance().stop();
    }

    @Override
    public synchronized void switchToIsolate() {
        disconnect();
        launchIsolated();
    }

    @Override
    public synchronized void disconnect() {
        connectionActions.invalidate();
        handshake.invalidate();
        leaveLocalPlayer();
        indi.mopelotus.musichud.network.PayloadFragments.resetClient();
        if (mode == ConnectionMode.EXTERNAL) leaveRemoteServer();
        MusicService.resetCurrentMusicStatus();
        NowPlayingInfo.getInstance().stop();
        StreamAudioPlayer.getInstance().stop();
        MusicHud.setConnectStatus(MusicHud.ConnectStatus.NOT_CONNECTED);
        mode = ConnectionMode.DISCONNECTED;
        connectGeneration.incrementAndGet();
    }

    @Override
    public synchronized Boolean toggleConnection() {
        MusicHud.ConnectStatus status = MusicHud.getConnectStatus();
        if (status != MusicHud.ConnectStatus.CONNECTED && status != MusicHud.ConnectStatus.NOT_CONNECTED
                && status != MusicHud.ConnectStatus.INCOMPATIBLE) return null;
        var minecraft = Minecraft.getInstance();
        Runnable action = connectionActions.prepare(minecraft::getConnection, () -> {
            if (minecraft.player == null || MusicHud.getConnectStatus() != status) return;
            if (status == MusicHud.ConnectStatus.CONNECTED) {
                if (clientConfig.getEnableIsolatedMode()) switchToIsolate(); else disconnect();
            } else connectToExternalServer();
        });
        java.util.concurrent.CompletableFuture.delayedExecutor(TOGGLE_DEBOUNCE_DELAY_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS, minecraft).execute(action);
        return status == MusicHud.ConnectStatus.CONNECTED;
    }

    @Override
    public void keyBindsToggleConnection() {
        boolean integratedServer = Minecraft.getInstance().getCurrentServer() == null;
        if (!integratedServer) {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastPressTime <= 3000) {
                lastPressTime = 0;
                Boolean connected = toggleConnection();
                if (connected != null) {
                    MuiModApi.postToUiThread(() -> {
                        //noinspection UnstableApiUsage
                        Context context = UIManager.getInstance().getDecorView().getContext();
                        if (connected) {
                            ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.disconnecting"), Toast.LENGTH_SHORT));
                        } else {
                            ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.connecting"), Toast.LENGTH_SHORT));
                        }
                    });
                }
            } else {
                lastPressTime = currentTimeMillis;
                MuiModApi.postToUiThread(() -> {
                    //noinspection UnstableApiUsage
                    Context context = UIManager.getInstance().getDecorView().getContext();
                    ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.confirmSwitchConnection"), Toast.LENGTH_SHORT));
                });
            }
        } else {
            MuiModApi.postToUiThread(() -> {
                //noinspection UnstableApiUsage
                Context context = UIManager.getInstance().getDecorView().getContext();
                ToastUtil.show(Toast.makeText(context, I18n.get(MusicHud.MOD_ID + ".text.switchConnectionUnavailableInIntegratedServer"), Toast.LENGTH_SHORT));
            });
        }
    }

    @Override
    public void connectAsPrevious() {
        if (mode == ConnectionMode.EXTERNAL) {
            IConnectionManager.getInstance().connectToExternalServer();
        } else {
            IConnectionManager.getInstance().launchIsolated();
        }
    }

    @Override
    public void onConnectResponse(ConnectResponse payload) {
        handshake.receive(payload, Minecraft.getInstance().player);
    }

    @Override
    public void onConnectResponse(ConnectResponse payload, indi.mopelotus.musichud.network.IPlayerClient origin) {
        if (origin instanceof VanillaPlayerProxy player) handshake.receive(payload, player.getPlayer());
    }

    private void requestInitialState() {
        ConnectionMode requestedMode = mode;
        int requestedGeneration = connectGeneration.get();
        requestInitialState(requestedMode, requestedGeneration, 0);
    }

    private void requestInitialState(ConnectionMode requestedMode, int requestedGeneration, int attempt) {
        MusicHud.EXECUTOR.execute(() -> {
            java.util.concurrent.CompletableFuture<GetInitialStateResponse> request;
            long requestedQueueRevision;
            synchronized (ConnectionManager.this) {
                if (mode != requestedMode || requestedGeneration != connectGeneration.get()
                        || (requestedMode == ConnectionMode.EXTERNAL
                        && MusicHud.getConnectStatus() != MusicHud.ConnectStatus.CONNECTED)) {
                    return;
                }
                requestedQueueRevision = MusicService.getInstance().queueRevision();
                request = RequestResponseManager.send(
                        new GetInitialStateRequest(), GetInitialStateResponse.class, Duration.ofSeconds(5));
            }
            request.thenAccept(response -> {
                        synchronized (ConnectionManager.this) {
                            if (mode != requestedMode || requestedGeneration != connectGeneration.get()
                                    || MusicHud.getConnectStatus() != MusicHud.ConnectStatus.CONNECTED && requestedMode == ConnectionMode.EXTERNAL) {
                                logger.debug("Initial state response ignored, connection changed from mode={} generation={}",
                                        requestedMode, requestedGeneration);
                                return;
                            }
                            MusicService.getInstance().refreshInitialQueue(requestedQueueRevision, response.getQueue());
                            MusicService.getInstance().switchMusic(
                                    response.getPlaybackSession(), response.getNextIdle(), "");
                            MusicService.getInstance().getIdlePlaySourceState().external().updateAll(
                                    response.getPlaylistSources(), response.getAlbumSources());
                        }
                    })
                    .exceptionally(e -> {
                        if (attempt < 2) {
                            logger.debug("Initial state attempt {} failed; retrying", attempt + 1, e);
                            requestInitialState(requestedMode, requestedGeneration, attempt + 1);
                        } else {
                            logger.warn("Failed to get initial state after {} attempts", attempt + 1, e);
                        }
                        return null;
                    });
        });
    }
}
