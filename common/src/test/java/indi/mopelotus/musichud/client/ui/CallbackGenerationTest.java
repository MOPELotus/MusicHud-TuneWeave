package indi.mopelotus.musichud.client.ui;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CallbackGenerationTest {
    @Test void queuedOldAddsAndRemovesCannotTouchRefreshedView() {
        var callbacks = new CallbackGeneration();
        List<Runnable> queue = new ArrayList<>();
        List<String> rendered = new ArrayList<>();
        long old = callbacks.next();
        callbacks.post(queue::add, old, () -> rendered.add("old add"));
        long fresh = callbacks.next();
        callbacks.post(queue::add, fresh, () -> rendered.add("new"));
        callbacks.post(queue::add, old, rendered::clear);
        queue.forEach(Runnable::run);
        assertEquals(List.of("new"), rendered);
    }

    @Test void detachInvalidatesPendingCompletionEvenBeforeReattach() {
        var callbacks = new CallbackGeneration();
        List<Runnable> queue = new ArrayList<>();
        callbacks.post(queue::add, callbacks.next(), () -> fail("Detached callback ran"));
        callbacks.next();
        queue.forEach(Runnable::run);
    }
}
