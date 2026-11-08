package indi.mopelotus.musichud.utils.collections;

import indi.mopelotus.musichud.MusicHud;
import java.util.function.Consumer;

/** One independent observer or recipient cannot interrupt delivery to the others. */
public final class ListenerDispatch {
    private ListenerDispatch() {}

    public static <T> void notifyEach(Iterable<T> listeners, Consumer<T> notify) {
        int failed = 0;
        for (T listener : listeners) {
            try { notify.accept(listener); }
            catch (RuntimeException failure) { failed++; }
        }
        if (failed > 0) MusicHud.getLogger(ListenerDispatch.class).warn("Skipped {} failed callbacks or recipients", failed);
    }

    public static void runEach(Iterable<Runnable> listeners) {
        notifyEach(listeners, Runnable::run);
    }
}
