package indi.mopelotus.musichud.client.ui;

import indi.mopelotus.musichud.beans.state.IIdlePlaySourceCollectionState;
import indi.mopelotus.musichud.interfaces.Unregister;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundSourceToggleTest {
    @Test void replacedOrDetachedBindingRejectsPendingAction() {
        var toggle = new BoundSourceToggle();
        var first = new Source();
        var second = new Source();
        long old = toggle.bind(first);
        Runnable stale = toggle.request(true);
        toggle.bind(second);
        stale.run();
        assertFalse(first.contained); assertFalse(second.contained);
        assertFalse(toggle.isBound(old));
        Runnable detached = toggle.request(true);
        toggle.bind(null); detached.run();
        assertFalse(second.contained);
    }

    @Test void latestIntentWinsAndExternalStateIsRecheckedAtExecution() {
        var toggle = new BoundSourceToggle(); var source = new Source(); toggle.bind(source);
        Runnable first = toggle.request(true), last = toggle.request(false);
        first.run(); last.run(); assertEquals(0, source.mutations);
        Runnable desired = toggle.request(true); source.contained = true; desired.run();
        assertEquals(0, source.mutations);
        toggle.request(false).run(); assertEquals(1, source.mutations); assertFalse(source.contained);
    }

    private static class Source implements IIdlePlaySourceCollectionState {
        boolean contained; int mutations;
        public long getCollectionId() { return 1; }
        public boolean isContained() { return contained; }
        public void add() { contained = true; mutations++; }
        public void remove() { contained = false; mutations++; }
        public Unregister onOthersModify(java.util.function.Consumer<Boolean> listener) { return () -> {}; }
    }
}
