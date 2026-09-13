package indi.mopelotus.musichud.platform.plugin.bungeecord.network;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Keeps complete public messages out of CONFIGURATION, with one bounded queue per physical peer. */
public final class BungeePlayGate implements AutoCloseable {
    public interface Transport {
        boolean ready();
        void execute(Runnable task);
        void later(Runnable task);
    }
    private static final int MAX_MESSAGES = 128;
    // Two maximum-size protocol messages including fragment envelopes (at most 164 bytes each).
    private static final int MAX_BYTES = 2 * (indi.mopelotus.musichud.network.PayloadFragments.MAX_BYTES
            + 256 * ((indi.mopelotus.musichud.network.PayloadFragments.MAX_BYTES
            + indi.mopelotus.musichud.network.PayloadFragments.CHUNK_BYTES - 1)
            / indi.mopelotus.musichud.network.PayloadFragments.CHUNK_BYTES));
    private final Map<Object, Pending> pending = new IdentityHashMap<>();
    private boolean closed;

    public synchronized void submit(Object peer, Object backend, BooleanSupplier active, Transport transport,
                                    int bytes, Runnable send, Runnable overflow) {
        if (closed || !active.getAsBoolean()) return;
        if (bytes < 0 || bytes > MAX_BYTES) throw new IllegalArgumentException("Invalid pending packet size");
        Pending state = pending.get(peer);
        if (state == null || state.backend != backend) {
            if (state != null) retire(state);
            state = new Pending(peer, backend, active, transport, overflow);
            pending.put(peer, state);
        }
        if (state.queue.size() >= MAX_MESSAGES || state.bytes + bytes > MAX_BYTES) {
            retire(state);
            state.overflow.run();
            return;
        }
        state.queue.addLast(new Delivery(bytes, send));
        state.bytes += bytes;
        if (!state.waiting) {
            state.waiting = true;
            Pending owner = state;
            state.transport.execute(() -> drain(owner));
        }
    }

    private synchronized void drain(Pending state) {
        if (!valid(state)) return;
        if (!state.transport.ready()) {
            state.waiting = true;
            state.transport.later(() -> drain(state));
            return;
        }
        while (!state.queue.isEmpty()) {
            if (!valid(state)) return;
            Delivery next = state.queue.removeFirst();
            state.bytes -= next.bytes;
            try { next.send.run(); }
            catch (RuntimeException | Error error) { retire(state); throw error; }
        }
        state.waiting = false;
    }

    public synchronized void cancel(Object peer) {
        Pending state = pending.get(peer);
        if (state != null) retire(state);
    }

    private boolean valid(Pending state) {
        if (closed || state.retired || pending.get(state.peer) != state || !state.active.getAsBoolean()) {
            retire(state);
            return false;
        }
        return true;
    }
    private void retire(Pending state) {
        state.retired = true;
        state.queue.clear();
        state.bytes = 0;
        pending.remove(state.peer, state);
    }
    @Override public synchronized void close() {
        closed = true;
        for (Pending state : pending.values()) {
            state.retired = true;
            state.queue.clear();
        }
        pending.clear();
    }
    private record Delivery(int bytes, Runnable send) {}
    private static final class Pending {
        final Object peer, backend;
        final BooleanSupplier active;
        final Transport transport;
        final Runnable overflow;
        final ArrayDeque<Delivery> queue = new ArrayDeque<>();
        int bytes;
        boolean waiting, retired;
        Pending(Object peer, Object backend, BooleanSupplier active, Transport transport, Runnable overflow) {
            this.peer = peer; this.backend = backend; this.active = active;
            this.transport = transport; this.overflow = overflow;
        }
    }
}
