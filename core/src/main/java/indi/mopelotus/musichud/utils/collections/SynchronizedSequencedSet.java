package indi.mopelotus.musichud.utils.collections;

import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class SynchronizedSequencedSet<E> implements SequencedSet<E> {
    private final SequencedSet<E> delegate;
    private final Object mutex;

    Object mutex() { return mutex; }

    public SynchronizedSequencedSet(SequencedSet<E> delegate) {
        this(delegate, new Object());
    }

    private SynchronizedSequencedSet(SequencedSet<E> delegate, Object mutex) {
        this.delegate = delegate;
        this.mutex = mutex;
    }

    @Override
    public int size() {
        synchronized (mutex) {
            return delegate.size();
        }
    }

    @Override
    public boolean isEmpty() {
        synchronized (mutex) {
            return delegate.isEmpty();
        }
    }

    @Override
    public boolean contains(Object o) {
        synchronized (mutex) {
            return delegate.contains(o);
        }
    }

    @Override
    public @NotNull Iterator<E> iterator() {
        synchronized (mutex) {
            return Collections.unmodifiableList(new ArrayList<>(delegate)).iterator();
        }
    }

    @Override
    public Object @NotNull [] toArray() {
        synchronized (mutex) {
            return delegate.toArray();
        }
    }

    @Override
    public <T> T @NotNull [] toArray(@NotNull T[] a) {
        synchronized (mutex) {
            return delegate.toArray(a);
        }
    }

    @Override
    public boolean add(E e) {
        synchronized (mutex) {
            return delegate.add(e);
        }
    }

    @Override
    public boolean remove(Object o) {
        synchronized (mutex) {
            return delegate.remove(o);
        }
    }

    @Override
    public boolean containsAll(@NotNull Collection<?> c) {
        synchronized (mutex) {
            return delegate.containsAll(c);
        }
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends E> c) {
        synchronized (mutex) {
            return delegate.addAll(c);
        }
    }

    @Override
    public boolean retainAll(@NotNull Collection<?> c) {
        synchronized (mutex) {
            return delegate.retainAll(c);
        }
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        synchronized (mutex) {
            return delegate.removeAll(c);
        }
    }

    @Override
    public void clear() {
        synchronized (mutex) {
            delegate.clear();
        }
    }

    @Override
    public E getFirst() {
        synchronized (mutex) {
            return delegate.getFirst();
        }
    }

    @Override
    public E getLast() {
        synchronized (mutex) {
            return delegate.getLast();
        }
    }

    @Override
    public void addFirst(E e) {
        synchronized (mutex) {
            delegate.addFirst(e);
        }
    }

    @Override
    public void addLast(E e) {
        synchronized (mutex) {
            delegate.addLast(e);
        }
    }

    @Override
    public E removeFirst() {
        synchronized (mutex) {
            return delegate.removeFirst();
        }
    }

    @Override
    public E removeLast() {
        synchronized (mutex) {
            return delegate.removeLast();
        }
    }

    @Override
    public boolean removeIf(@NotNull Predicate<? super E> filter) {
        synchronized (mutex) {
            return delegate.removeIf(filter);
        }
    }

    @Override
    public @NotNull SequencedSet<E> reversed() {
        synchronized (mutex) {
            return new SynchronizedSequencedSet<>(delegate.reversed(), mutex);
        }
    }

    @Override
    public @NotNull Spliterator<E> spliterator() {
        synchronized (mutex) {
            return new ArrayList<>(delegate).spliterator();
        }
    }

    @Override
    public @NotNull Stream<E> stream() {
        synchronized (mutex) {
            return new ArrayList<>(delegate).stream();
        }
    }

    @Override
    public @NotNull Stream<E> parallelStream() {
        synchronized (mutex) {
            return new ArrayList<>(delegate).parallelStream();
        }
    }

    @Override
    public void forEach(@NotNull Consumer<? super E> action) {
        List<E> snapshot;
        synchronized (mutex) { snapshot = new ArrayList<>(delegate); }
        snapshot.forEach(action);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        Set<E> snapshot;
        synchronized (mutex) { snapshot = new LinkedHashSet<>(delegate); }
        return snapshot.equals(obj);
    }

    @Override
    public int hashCode() {
        synchronized (mutex) {
            return delegate.hashCode();
        }
    }
}
