package indi.mopelotus.musichud.client.services;

import indi.mopelotus.musichud.MusicHud.ConnectStatus;
import indi.mopelotus.musichud.interfaces.IConnectionManager.ConnectionMode;
import indi.mopelotus.musichud.network.ClientPacketContext;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.Function;
import java.util.function.Consumer;

/** Capturing never takes the connection lock: local server senders may already hold a server-state lock. */
public final class ClientPayloadAdmission {
    /** Called on the client thread, after any platform queue and before capturing admission. */
    public static <P> void receiveFromListener(Object originListener, Object currentListener,
                                               Supplier<P> currentPlayer,
                                               Function<P, ClientPacketContext.Admission> capture,
                                               Consumer<P> receive) {
        // A proxy switch reuses the TCP connection but replaces the PLAY listener.
        if (currentListener == null || originListener != currentListener) return;
        P player = currentPlayer.get();
        if (player == null) return;
        ClientPacketContext.receive(capture.apply(player), () -> receive.accept(player));
    }

    private final Object lock;
    private final IntSupplier generation;
    private final Supplier<Object> connection, player;
    private final Supplier<ConnectionMode> mode;
    private final Supplier<ConnectStatus> status;
    private final BooleanSupplier integrated, enabled;

    public ClientPayloadAdmission(Object lock, IntSupplier generation, Supplier<Object> connection,
                                  Supplier<Object> player, Supplier<ConnectionMode> mode,
                                  Supplier<ConnectStatus> status, BooleanSupplier integrated, BooleanSupplier enabled) {
        this.lock = lock;
        this.generation = generation;
        this.connection = connection;
        this.player = player;
        this.mode = mode;
        this.status = status;
        this.integrated = integrated;
        this.enabled = enabled;
    }

    public ClientPacketContext.Admission capture(boolean remote, Object originPlayer, boolean handshake) {
        int expectedGeneration = generation.getAsInt();
        Object expectedConnection = connection.get();
        ConnectionMode expectedMode = mode.get();
        return new ClientPacketContext.Admission() {
            public boolean isCurrent() {
                if (!enabled.getAsBoolean() || expectedGeneration != generation.getAsInt()
                        || expectedConnection == null || expectedConnection != connection.get()
                        || originPlayer == null || originPlayer != player.get() || expectedMode != mode.get()) return false;
                // Retired handshakes may only reach the controller's identity-checked remote cleanup.
                if (handshake) return expectedMode != ConnectionMode.EXTERNAL || status.get() == ConnectStatus.NOT_CONNECTED;
                return remote ? expectedMode == ConnectionMode.EXTERNAL && status.get() == ConnectStatus.CONNECTED
                        : expectedMode == ConnectionMode.ISOLATED
                            || integrated.getAsBoolean() && expectedMode == ConnectionMode.EXTERNAL && status.get() == ConnectStatus.CONNECTED;
            }
            public void runIfCurrent(Runnable action) {
                synchronized (lock) { if (isCurrent()) action.run(); }
            }
        };
    }
}
