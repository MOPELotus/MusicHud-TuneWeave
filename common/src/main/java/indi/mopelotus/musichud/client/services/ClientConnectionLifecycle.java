package indi.mopelotus.musichud.client.services;

import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** World callbacks and player replacement use the same owner lock as packet publication. */
public final class ClientConnectionLifecycle {
    private final Object lock;
    private final Supplier<Object> connection, transport, player;
    private final Executor executor;
    private Object boundConnection, boundTransport, boundPlayer, activeTransport, activePlayer, epoch = new Object();
    private Runnable initialJoin;

    public ClientConnectionLifecycle(Object lock, Supplier<Object> connection, Supplier<Object> transport,
                                     Supplier<Object> player, Executor executor) {
        this.lock = lock;
        this.connection = connection;
        this.transport = transport;
        this.player = player;
        this.executor = executor;
    }

    public void join(Object origin, Runnable action, Consumer<Object> replacePlayer) {
        synchronized (lock) {
            Object listener = connection.get(), physical = transport.get();
            if (listener == null || physical == null || origin == null || origin != player.get()) return;
            if (boundConnection == listener && boundTransport == physical && boundPlayer == origin) return;
            boundConnection = listener;
            boundTransport = physical;
            boundPlayer = origin;
            initialJoin = action;
            Object ticket = epoch = new Object();
            executor.execute(() -> {
                synchronized (lock) {
                    if (epoch != ticket || boundConnection != connection.get() || boundTransport != transport.get()
                            || boundPlayer != player.get()) return;
                    boolean continuation = activePlayer != null && activeTransport == physical;
                    Object previous = activePlayer;
                    activePlayer = origin;
                    activeTransport = physical;
                    if (continuation) replacePlayer.accept(previous);
                    else action.run();
                }
            });
        }
    }

    public void quit(Object origin, Object originConnection, Consumer<Object> detach, Runnable cleanup) {
        synchronized (lock) {
            if (origin == null || boundConnection == null || originConnection != boundConnection) return;
            Object physical = boundConnection;
            Object previousPlayer = activePlayer == null ? boundPlayer : activePlayer;
            boundConnection = boundTransport = boundPlayer = activeTransport = activePlayer = null;
            initialJoin = null;
            Object ticket = epoch = new Object();
            detach.accept(previousPlayer);
            executor.execute(() -> {
                synchronized (lock) {
                    Object current = connection.get();
                    if (epoch == ticket && boundConnection == null && (current == null || current == physical)) cleanup.run();
                }
            });
        }
    }

    /** A transport can close during configuration, when there is no current player or PLAY quit event. */
    public void disconnectIfClosed(Predicate<Object> connected, Consumer<Object> detach, Runnable cleanup) {
        synchronized (lock) {
            if (boundTransport != null && !connected.test(boundTransport)) {
                quit(boundPlayer, boundConnection, detach, cleanup);
            }
        }
    }

    public void tick(Consumer<Object> replacePlayer) {
        synchronized (lock) {
            Object current = player.get();
            if (boundConnection == null || boundConnection != connection.get() || boundTransport != transport.get()
                    || current == null || current == boundPlayer) return;
            if (activePlayer == null || activeTransport != boundTransport) {
                // Respawn may replace the player before the first queued connection action runs.
                join(current, initialJoin, replacePlayer);
                return;
            }
            Object old = activePlayer;
            boundPlayer = current;
            activePlayer = current;
            epoch = new Object();
            replacePlayer.accept(old);
        }
    }
}
