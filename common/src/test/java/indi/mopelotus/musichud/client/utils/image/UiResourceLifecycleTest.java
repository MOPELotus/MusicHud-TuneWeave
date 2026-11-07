package indi.mopelotus.musichud.client.utils.image;

import icyllis.modernui.lifecycle.Lifecycle;
import icyllis.modernui.lifecycle.LifecycleObserver;
import icyllis.modernui.lifecycle.LifecycleOwner;
import indi.mopelotus.musichud.client.utils.OwnedResources;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UiResourceLifecycleTest {
    static final class Owner extends Lifecycle implements LifecycleOwner {
        State state = State.STARTED;
        List<LifecycleObserver> observers = new ArrayList<>();
        public Lifecycle getLifecycle() { return this; }
        public State getCurrentState() { return state; }
        public void addObserver(LifecycleObserver observer) { observers.add(observer); }
        public void removeObserver(LifecycleObserver observer) { observers.remove(observer); }
        void destroy() {
            state = State.DESTROYED;
            for (var observer : List.copyOf(observers)) {
                observer.onDestroy(this);
                observer.onStateChanged(this, Event.ON_DESTROY);
            }
        }
    }

    @Test void destroysOwnedImagesOnceAndRejectsLateAllocation() {
        var owner = new Owner();
        var closed = new AtomicInteger();
        var resources = new OwnedResources(error -> fail(error));
        AutoCloseable image = resources.create(() -> closed::incrementAndGet);
        UiResourceLifecycle.bind(owner, resources::close);
        assertEquals(0, closed.get());
        owner.destroy();
        owner.destroy();
        resources.release(image);
        assertEquals(1, closed.get());
        assertTrue(owner.observers.isEmpty());
        assertThrows(CancellationException.class, () -> resources.create(() -> closed::incrementAndGet));
    }

    @Test void lateSubscriptionClosesImmediatelyWithoutRetainingObserver() {
        var owner = new Owner();
        owner.destroy();
        var closed = new AtomicInteger();
        UiResourceLifecycle.bind(owner, closed::incrementAndGet);
        assertEquals(1, closed.get());
        assertTrue(owner.observers.isEmpty());
    }
}
