package indi.mopelotus.musichud.utils.collections;

import com.google.common.collect.ForwardingSet;
import indi.mopelotus.musichud.interfaces.Unregister;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Ordered collections shared by background loaders and UI observers. */
public class ObservableSequencedSet<E> extends ForwardingSet<E> implements SequencedSet<E> {
    private final SynchronizedSequencedSet<E> delegate;
    private final Set<Runnable> changeListeners;
    private final Set<Consumer<E>> addListeners;
    private final Set<Consumer<E>> removeListeners;

    public ObservableSequencedSet(SequencedSet<E> delegate) {
        this(new SynchronizedSequencedSet<>(delegate), ConcurrentHashMap.newKeySet(),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet());
    }

    private ObservableSequencedSet(SynchronizedSequencedSet<E> delegate, Set<Runnable> changes,
                                   Set<Consumer<E>> additions, Set<Consumer<E>> removals) {
        this.delegate = delegate;
        changeListeners = changes;
        addListeners = additions;
        removeListeners = removals;
    }

    public ObservableSequencedSet(Integer capacity) { this(new LinkedHashSet<>(capacity)); }
    public ObservableSequencedSet() { this(0); }

    @Override @NotNull protected Set<E> delegate() { return delegate; }

    public List<E> snapshot() {
        synchronized (delegate.mutex()) { return new ArrayList<>(delegate); }
    }

    // Publish only after releasing the collection lock: observers may consult another collection.
    private void changed(List<E> removed, List<E> added) {
        removed.forEach(e -> removeListeners.forEach(listener -> listener.accept(e)));
        added.forEach(e -> addListeners.forEach(listener -> listener.accept(e)));
        changeListeners.forEach(Runnable::run);
    }

    @Override public boolean add(E element) {
        synchronized (delegate.mutex()) { if (!delegate.add(element)) return false; }
        changed(List.of(), Collections.singletonList(element));
        return true;
    }

    @Override public void addFirst(E element) { addAtEnd(element, true); }
    @Override public void addLast(E element) { addAtEnd(element, false); }

    private void addAtEnd(E element, boolean first) {
        boolean added;
        synchronized (delegate.mutex()) {
            if (!delegate.isEmpty() && Objects.equals(first ? delegate.getFirst() : delegate.getLast(), element)) return;
            added = !delegate.contains(element);
            if (first) delegate.addFirst(element); else delegate.addLast(element);
        }
        changed(List.of(), added ? Collections.singletonList(element) : List.of());
    }

    @Override public E getFirst() { return delegate.getFirst(); }
    @Override public E getLast() { return delegate.getLast(); }
    @Override public E removeFirst() { return removeEnd(true); }
    @Override public E removeLast() { return removeEnd(false); }

    private E removeEnd(boolean first) {
        E element;
        synchronized (delegate.mutex()) { element = first ? delegate.removeFirst() : delegate.removeLast(); }
        changed(Collections.singletonList(element), List.of());
        return element;
    }

    @Override public boolean remove(Object element) {
        E removed;
        synchronized (delegate.mutex()) {
            var found = delegate.stream().filter(value -> Objects.equals(value, element)).toList();
            if (found.isEmpty()) return false;
            removed = found.getFirst();
            delegate.remove(element);
        }
        changed(Collections.singletonList(removed), List.of());
        return true;
    }

    @Override public boolean addAll(@NotNull Collection<? extends E> elements) {
        List<? extends E> input = new ArrayList<>(elements);
        List<E> added = new ArrayList<>();
        synchronized (delegate.mutex()) {
            for (E element : input) if (delegate.add(element)) added.add(element);
        }
        if (added.isEmpty()) return false;
        changed(List.of(), added);
        return true;
    }

    @Override public boolean removeAll(@NotNull Collection<?> elements) {
        Set<?> selected = new HashSet<>(elements);
        return removeIf(selected::contains);
    }

    @Override public boolean retainAll(@NotNull Collection<?> elements) {
        Set<?> retained = new HashSet<>(elements);
        return removeIf(element -> !retained.contains(element));
    }

    @Override public boolean removeIf(@NotNull Predicate<? super E> filter) {
        Objects.requireNonNull(filter);
        List<E> removed;
        synchronized (delegate.mutex()) {
            removed = delegate.stream().filter(filter).toList();
            delegate.removeAll(removed);
        }
        if (removed.isEmpty()) return false;
        changed(removed, List.of());
        return true;
    }

    @Override public void clear() {
        List<E> removed;
        synchronized (delegate.mutex()) {
            removed = snapshot();
            delegate.clear();
        }
        if (!removed.isEmpty()) changed(removed, List.of());
    }

    /** Replace ordering and data atomically, including reorders without additions/removals. */
    public void syncWith(ObservableSequencedSet<E> target, boolean notify) {
        List<E> wanted = target.snapshot();
        List<E> removed, added;
        synchronized (delegate.mutex()) {
            List<E> previous = snapshot();
            if (previous.equals(wanted)) return;
            Set<E> oldSet = new HashSet<>(previous), newSet = new HashSet<>(wanted);
            removed = previous.stream().filter(e -> !newSet.contains(e)).toList();
            added = wanted.stream().filter(e -> !oldSet.contains(e)).toList();
            delegate.clear();
            delegate.addAll(wanted);
        }
        if (notify) changed(removed, added);
    }

    @Override @NotNull public Iterator<E> iterator() {
        Iterator<E> snapshot = snapshot().iterator();
        return new Iterator<>() {
            E current;
            boolean removable;
            public boolean hasNext() { return snapshot.hasNext(); }
            public E next() { current = snapshot.next(); removable = true; return current; }
            public void remove() {
                if (!removable) throw new IllegalStateException();
                removable = false;
                ObservableSequencedSet.this.remove(current);
            }
        };
    }

    @Override @NotNull public SequencedSet<E> reversed() {
        return new ObservableSequencedSet<>((SynchronizedSequencedSet<E>) delegate.reversed(),
                changeListeners, addListeners, removeListeners);
    }

    public EditHandle<E> beginEdit() { return new EditHandle<>(this); }
    public static class EditHandle<E> {
        private final ObservableSequencedSet<E> set;
        private final List<E> snapshot;
        EditHandle(ObservableSequencedSet<E> set) { this.set = set; snapshot = set.snapshot(); }
        public void rollback() { set.syncWith(new ObservableSequencedSet<>(new LinkedHashSet<>(snapshot)), true); }
        public void commit() {}
    }

    public Unregister registerOnChange(Runnable listener) {
        changeListeners.add(listener); return () -> changeListeners.remove(listener);
    }
    public Unregister registerOnAdd(Consumer<E> listener) {
        addListeners.add(listener); return () -> addListeners.remove(listener);
    }
    public Unregister registerOnRemove(Consumer<E> listener) {
        removeListeners.add(listener); return () -> removeListeners.remove(listener);
    }
}
