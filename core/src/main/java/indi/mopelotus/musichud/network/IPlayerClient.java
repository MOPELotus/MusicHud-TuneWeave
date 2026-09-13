package indi.mopelotus.musichud.network;

import java.util.UUID;

public interface IPlayerClient {
    enum ClientType {
        LOCAL, REMOTE
    }
    UUID getUUID();
    String getName();
    ClientType getClientType();
    default boolean isConnected() { return true; }
    default Object connectionIdentity() { return this; }
    /** Stable transport key for ordering handshake controls across player respawn. */
    default Object controlConnectionIdentity() { return connectionIdentity(); }
}
