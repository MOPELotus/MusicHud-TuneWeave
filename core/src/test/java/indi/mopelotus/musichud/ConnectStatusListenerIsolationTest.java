package indi.mopelotus.musichud;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class ConnectStatusListenerIsolationTest {
    @Test void observersMayDetachThemselvesWithoutBlockingOtherObservers() {
        var listeners = MusicHud.getConnectStatusListeners();
        var previous = java.util.List.copyOf(listeners);
        var status = MusicHud.getConnectStatus();
        listeners.clear();
        try {
            var calls = new AtomicInteger();
            Consumer<MusicHud.ConnectStatus> selfRemoving = new Consumer<>() {
                @Override public void accept(MusicHud.ConnectStatus ignored) { listeners.remove(this); }
            };
            listeners.add(selfRemoving);
            listeners.add(ignored -> { throw new IllegalStateException("broken observer"); });
            listeners.add(ignored -> calls.incrementAndGet());
            assertDoesNotThrow(() -> MusicHud.setConnectStatus(MusicHud.ConnectStatus.CONNECTED));
            assertEquals(1, calls.get());
            assertFalse(listeners.contains(selfRemoving));
        } finally {
            listeners.clear(); MusicHud.setConnectStatus(status); listeners.addAll(previous);
        }
    }
}
