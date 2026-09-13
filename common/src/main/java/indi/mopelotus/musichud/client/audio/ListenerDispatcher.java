package indi.mopelotus.musichud.client.audio;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class ListenerDispatcher {
    private ListenerDispatcher() {
    }

    static <T> void dispatch(Collection<? extends T> listeners,
                             Consumer<? super T> invocation,
                             Consumer<? super RuntimeException> failureHandler) {
        Objects.requireNonNull(listeners, "listeners");
        Objects.requireNonNull(invocation, "invocation");
        Objects.requireNonNull(failureHandler, "failureHandler");
        for (T listener : List.copyOf(listeners)) {
            try {
                invocation.accept(listener);
            } catch (RuntimeException error) {
                failureHandler.accept(error);
            }
        }
    }
}
