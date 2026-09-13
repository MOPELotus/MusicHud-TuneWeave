package indi.mopelotus.musichud.client.ui.pages;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

final class ActiveViewReference<T> {
    private final AtomicReference<T> current = new AtomicReference<>();

    void attach(T view) {
        current.set(Objects.requireNonNull(view, "view"));
    }

    boolean detach(T view) {
        return current.compareAndSet(Objects.requireNonNull(view, "view"), null);
    }

    boolean isCurrent(T view) {
        return current.get() == view;
    }

    T get() {
        return current.get();
    }
}
