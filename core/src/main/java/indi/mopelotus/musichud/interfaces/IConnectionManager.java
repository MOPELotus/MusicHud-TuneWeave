package indi.mopelotus.musichud.interfaces;

import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.network.payloads.requestResponseCycle.ConnectResponse;
import indi.mopelotus.musichud.platform.Environment;

import java.util.function.Supplier;

/**
 * Centralized control of connection mode switching (external server vs isolated client).
 * Previously scattered across LoginService / MusicService / ConnectResponse handling,
 * which caused several bugs.
 */
public interface IConnectionManager {
    static IConnectionManager getInstance() {
        Environment currentEnvironment = MusicHud.getCurrentEnvironment();
        if (currentEnvironment.getSide() == Environment.Side.CLIENT) {
            Environment.Platform platform = currentEnvironment.getPlatform();
            Supplier<IConnectionManager> supplier = platform.getConnectionManagerSupplier();
            if (supplier != null) {
                IConnectionManager connectionManager = supplier.get();
                if (connectionManager != null) {
                    return connectionManager;
                }
            }
        }
        throw new UnsupportedOperationException();
    }

    enum ConnectionMode {
        DISCONNECTED, EXTERNAL, ISOLATED
    }

    ConnectionMode getMode();

    void connectToExternalServer();

    void launchIsolated();

    void switchToIsolate();

    void disconnect();

    Boolean toggleConnection();

    void keyBindsToggleConnection();


    void connectAsPrevious();

    /**
     * Handles a ConnectResponse from the server: drives the connection state machine.
     */
    void onConnectResponse(ConnectResponse response);

    /** Transport origin is local metadata; it is never added to the wire handshake. */
    default void onConnectResponse(ConnectResponse response, indi.mopelotus.musichud.network.IPlayerClient origin) {
        onConnectResponse(response);
    }
}
