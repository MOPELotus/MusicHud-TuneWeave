package indi.mopelotus.musichud.client.services;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionActionGateTest {
    @Test void disconnectOrReplacementPreventsQueuedActionEvenForEqualConnections() {
        var connection = new AtomicReference<Object>(new String("same"));
        var gate = new ConnectionActionGate(new Object()); var calls = new AtomicInteger();
        Runnable old = gate.prepare(connection::get, calls::incrementAndGet);
        connection.set(new String("same")); old.run(); assertEquals(0, calls.get());
        Runnable next = gate.prepare(connection::get, calls::incrementAndGet);
        gate.invalidate(); next.run(); assertEquals(0, calls.get());
    }
    @Test void rapidActionsOnlyExecuteLatestOnceAndNullConnectionNeverExecutes() {
        var connection = new AtomicReference<Object>(new Object());
        var gate = new ConnectionActionGate(new Object()); var calls = new AtomicInteger();
        Runnable old = gate.prepare(connection::get, () -> fail());
        Runnable next = gate.prepare(connection::get, () -> { calls.incrementAndGet(); gate.invalidate(); });
        old.run(); next.run(); next.run(); assertEquals(1, calls.get());
        connection.set(null); gate.prepare(connection::get, () -> fail()).run();
    }
}
