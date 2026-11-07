package indi.mopelotus.musichud.client.utils.image;

import icyllis.modernui.lifecycle.Lifecycle;
import icyllis.modernui.lifecycle.LifecycleObserver;
import icyllis.modernui.lifecycle.LifecycleOwner;

/** Subscribes our resources to the host's public lifecycle, entirely on its UI thread. */
final class UiResourceLifecycle {
    private UiResourceLifecycle() {}

    static void bind(Lifecycle lifecycle, Runnable close) {
        if (lifecycle.getCurrentState() == Lifecycle.State.DESTROYED) {
            close.run();
            return;
        }
        lifecycle.addObserver(new LifecycleObserver() {
            private boolean closed;
            @Override public void onDestroy(LifecycleOwner owner) {
                if (closed) return;
                closed = true;
                lifecycle.removeObserver(this);
                close.run();
            }
        });
    }
}
