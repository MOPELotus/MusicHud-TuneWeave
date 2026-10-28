package indi.mopelotus.musichud.client.ui.hud;

import icyllis.modernui.mc.MuiModApi;
import java.util.function.Consumer;

/** Tracks the target window metrics, including scale changes that keep the framebuffer unchanged. */
final class HudEditorViewport {
    record Metrics(int width, int height, double guiScale) {
        int guiWidth() { return (int) Math.ceil(width / guiScale); }
        int guiHeight() { return (int) Math.ceil(height / guiScale); }
    }

    private final Consumer<Runnable> uiDispatcher;
    private final Consumer<MuiModApi.OnWindowResizeListener> subscribe;
    private final Consumer<MuiModApi.OnWindowResizeListener> unsubscribe;
    private final Runnable changed;
    private Metrics metrics;
    private MuiModApi.OnWindowResizeListener listener;
    private long attachmentEpoch;
    private long revision;

    HudEditorViewport(Metrics initial, Consumer<Runnable> uiDispatcher,
                      Consumer<MuiModApi.OnWindowResizeListener> subscribe,
                      Consumer<MuiModApi.OnWindowResizeListener> unsubscribe, Runnable changed) {
        metrics = initial;
        this.uiDispatcher = uiDispatcher;
        this.subscribe = subscribe;
        this.unsubscribe = unsubscribe;
        this.changed = changed;
    }

    synchronized Metrics metrics() { return metrics; }

    synchronized void attach(Metrics current) {
        metrics = current;
        revision++;
        if (listener == null) {
            long epoch = ++attachmentEpoch;
            listener = (width, height, scale, oldScale) -> onWindowResize(epoch, width, height, scale);
            subscribe.accept(listener);
        }
    }

    synchronized void detach() {
        revision++;
        attachmentEpoch++;
        if (listener != null) {
            unsubscribe.accept(listener);
            listener = null;
        }
    }

    private void onWindowResize(long epoch, int width, int height, int guiScale) {
        final long requested;
        synchronized (this) {
            if (listener == null || attachmentEpoch != epoch) return;
            requested = ++revision;
        }
        // The host dispatches before Window.setGuiScale writes its fields. Use the
        // target payload instead of reading the old Minecraft scale on the UI thread.
        Metrics target = new Metrics(width, height, guiScale);
        uiDispatcher.accept(() -> {
            synchronized (this) {
                if (listener == null || attachmentEpoch != epoch || revision != requested) return;
                metrics = target;
            }
            changed.run();
        });
    }
}
