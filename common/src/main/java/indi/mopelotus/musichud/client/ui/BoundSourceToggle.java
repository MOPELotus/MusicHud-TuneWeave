package indi.mopelotus.musichud.client.ui;

import indi.mopelotus.musichud.beans.state.IIdlePlaySourceCollectionState;

/** Binds delayed mutations to both a source identity and the latest user intent. */
public final class BoundSourceToggle {
    private IIdlePlaySourceCollectionState source;
    private long binding;
    private long request;

    public synchronized long bind(IIdlePlaySourceCollectionState source) {
        this.source = source;
        request++;
        return ++binding;
    }

    public synchronized boolean isBound(long expected) { return binding == expected; }

    public synchronized Runnable request(boolean target) {
        IIdlePlaySourceCollectionState captured = source;
        long expectedBinding = binding, expectedRequest = ++request;
        return () -> {
            synchronized (BoundSourceToggle.this) {
                if (captured == null || source != captured || binding != expectedBinding || request != expectedRequest) return;
                if (captured.isContained() == target) return;
                if (target) captured.add(); else captured.remove();
            }
        };
    }
}
