package indi.mopelotus.musichud.utils.collections;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ObservableSequencedSetTest {
    @Test void batchAddNotifiesOncePerNewItemAndReorderNotifiesOnlyChange() {
        var set = new ObservableSequencedSet<Integer>();
        var added = new ArrayList<Integer>();
        var changed = new AtomicInteger();
        set.registerOnAdd(added::add);
        set.registerOnChange(changed::incrementAndGet);
        set.addAll(List.of(1, 2, 2, 3));
        assertEquals(List.of(1, 2, 3), added);
        assertEquals(1, changed.get());
        set.addFirst(3);
        assertEquals(List.of(3, 1, 2), set.snapshot());
        assertEquals(List.of(1, 2, 3), added);
        assertEquals(2, changed.get());
        set.syncWith(new ObservableSequencedSet<>(new LinkedHashSet<>(List.of(2, 1, 3))), true);
        assertEquals(List.of(2, 1, 3), set.snapshot());
        assertEquals(3, changed.get());
    }

    @Test void reversedViewsAndSnapshotIteratorsMutateTheOriginalAndNotifyObservers() {
        var set = new ObservableSequencedSet<Integer>();
        set.addAll(List.of(1, 2, 3));
        var removed = new ArrayList<Integer>();
        var added = new ArrayList<Integer>();
        set.registerOnRemove(removed::add);
        set.registerOnAdd(added::add);
        var iterator = set.iterator();
        set.reversed().addFirst(4);
        assertEquals(List.of(4), added);
        assertEquals(1, iterator.next());
        iterator.remove();
        assertThrows(IllegalStateException.class, iterator::remove);
        assertEquals(List.of(1), removed);
        assertEquals(List.of(2, 3, 4), set.snapshot());
        set.reversed().removeFirst();
        assertEquals(List.of(1, 4), removed);
        assertEquals(List.of(2, 3), set.snapshot());
        assertEquals(List.of(2, 3), remaining(iterator));
    }

    @Test void rollbackPublishesOnlyTheFinalRestoredSnapshot() {
        var set = new ObservableSequencedSet<Integer>();
        set.addAll(List.of(1, 2, 3));
        var edit = set.beginEdit();
        set.clear();
        set.add(8);
        var observed = new ArrayList<List<Integer>>();
        set.registerOnChange(() -> observed.add(set.snapshot()));
        edit.rollback();
        assertEquals(List.of(List.of(1, 2, 3)), observed);
    }

    @Test @Timeout(10) void concurrentLoaderAndReversedViewNeverCorruptUiIteration() throws Exception {
        var set = new ObservableSequencedSet<Integer>();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var loader = executor.submit(() -> {
                start.await();
                for (int i = 0; i < 3000; i++) { set.add(i); if (i >= 100) set.remove(i - 100); }
                return null;
            });
            var reverse = executor.submit(() -> {
                start.await();
                var view = set.reversed();
                for (int i = 0; i < 3000; i++) { view.addFirst(-i - 1); view.remove(-i - 1); }
                return null;
            });
            start.countDown();
            for (int i = 0; i < 3000; i++) {
                var snapshot = new ArrayList<Integer>();
                for (Integer value : set) snapshot.add(value);
                assertEquals(snapshot.size(), new HashSet<>(snapshot).size());
            }
            loader.get(); reverse.get();
        }
        assertEquals(100, set.size());
        assertEquals(2900, set.getFirst());
        assertEquals(2999, set.getLast());
    }

    private static <T> List<T> remaining(Iterator<T> iterator) {
        var result = new ArrayList<T>(); iterator.forEachRemaining(result::add); return result;
    }
}
