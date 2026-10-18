package indi.mopelotus.musichud.client.ui.hud.renderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Render-thread-owned, interruptible cross-fade; independent of Minecraft and wall-clock time. */
final class SkinTransition<T> {
    static final int MAX_LAYERS = 8;
    record Layer<T>(T skin, float alpha) {}

    private final long durationNanos;
    private final List<Layer<T>> starts = new ArrayList<>();
    private T target;
    private long startNanos;
    private long lastNanos;
    private boolean initialized;

    SkinTransition(long durationNanos) {
        if (durationNanos <= 0) throw new IllegalArgumentException("Positive duration required");
        this.durationNanos = durationNanos;
    }

    boolean isVisible() { return !starts.isEmpty(); }

    List<Layer<T>> sample(T next, long now) {
        if (!initialized) {
            initialized = true;
            startNanos = lastNanos = now;
        }
        // A caller's regressing timestamp cannot rewind a visible transition. Subtraction also
        // tolerates the normal signed wrap of System.nanoTime().
        if (now - lastNanos < 0) now = lastNanos;
        lastNanos = now;
        List<Layer<T>> visible = interpolate(now);
        if (!Objects.equals(target, next)) {
            starts.clear();
            starts.addAll(visible);
            target = next;
            if (next != null && starts.stream().noneMatch(layer -> layer.skin().equals(next))) {
                if (starts.size() == MAX_LAYERS) compact();
                starts.add(new Layer<>(next, 0));
            }
            startNanos = now;
            return List.copyOf(starts);
        }
        if (now - startNanos >= durationNanos) {
            starts.clear();
            starts.addAll(visible);
        }
        return List.copyOf(visible);
    }

    private List<Layer<T>> interpolate(long now) {
        double linear = Math.clamp((double) (now - startNanos) / durationNanos, 0, 1);
        float progress = (float) ((1 - Math.cos(Math.PI * linear)) / 2);
        List<Layer<T>> result = new ArrayList<>(starts.size());
        for (Layer<T> layer : starts) {
            float goal = Objects.equals(layer.skin(), target) ? 1 : 0;
            float alpha = layer.alpha() + (goal - layer.alpha()) * progress;
            if (alpha > 0) result.add(new Layer<>(layer.skin(), alpha));
        }
        return result;
    }

    private void compact() {
        // Pathological changes faster than a fade can accumulate arbitrarily many skins.
        // Fold only the faintest old layer into the strongest old layer, retaining total alpha
        // and a fixed allocation/draw bound. Ordinary interrupted fades retain every layer.
        int faintest = 0;
        for (int i = 1; i < starts.size(); i++) {
            if (starts.get(i).alpha() < starts.get(faintest).alpha()) faintest = i;
        }
        float removed = starts.remove(faintest).alpha();
        int strongest = 0;
        for (int i = 1; i < starts.size(); i++) {
            if (starts.get(i).alpha() > starts.get(strongest).alpha()) strongest = i;
        }
        Layer<T> layer = starts.get(strongest);
        starts.set(strongest, new Layer<>(layer.skin(), layer.alpha() + removed));
    }
}
