package indi.mopelotus.musichud.client.services.tuneweave;

import com.google.gson.JsonElement;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.*;

/** Bounded, account-keyed page checkpoints. Only validated pages advance a cursor. */
final class ResumableOffsetCollection<K, T> {
    record Snapshot<T>(List<T> items, int nextOffset, boolean complete) {
        Snapshot { items = List.copyOf(items); }
    }
    private static final long FRESHNESS = 300_000;
    private final LongSupplier clock;
    private final LinkedHashMap<K, Entry<T>> entries = new LinkedHashMap<>(16, .75f, true);
    private static final class Entry<T> {
        final long created;
        Object request;
        Snapshot<T> snapshot = new Snapshot<>(List.of(), 0, false);
        Entry(long created) { this.created = created; }
    }

    ResumableOffsetCollection() { this(System::currentTimeMillis); }
    ResumableOffsetCollection(LongSupplier clock) { this.clock = clock; }
    synchronized void clear() { entries.clear(); }

    synchronized Snapshot<T> peekFresh(K key) {
        Entry<T> entry = entries.get(key);
        long now = clock.getAsLong();
        if (entry == null || now < entry.created || now - entry.created >= FRESHNESS) return null;
        return entry.snapshot;
    }

    Snapshot<T> load(K key, boolean refresh, IntFunction<TuneWeaveApiClient.TuneWeaveResponse> request,
                     Function<JsonElement, T> mapper, Function<T, String> identity,
                     Consumer<Snapshot<T>> publish) {
        Entry<T> entry;
        Object ticket = new Object();
        Snapshot<T> initial;
        synchronized (this) {
            long now = clock.getAsLong();
            entry = entries.get(key);
            if (refresh || entry == null || now < entry.created || now - entry.created >= FRESHNESS) {
                entry = new Entry<>(now);
                entries.put(key, entry);
                while (entries.size() > 50) entries.remove(entries.keySet().iterator().next());
            }
            entry.request = ticket;
            initial = entry.snapshot;
        }
        Entry<T> active = entry;
        requireCurrent(key, active, ticket);
        if (!initial.items().isEmpty() || initial.complete()) publish.accept(initial);
        requireCurrent(key, active, ticket);
        if (initial.complete()) return initial;
        LinkedHashMap<String, T> merged = new LinkedHashMap<>();
        initial.items().forEach(item -> merged.put(identity.apply(item), item));
        OffsetPagination.walk(request, initial.nextOffset(), page -> {
            for (JsonElement raw : page.items()) {
                T item = mapper.apply(raw);
                if (item != null) merged.putIfAbsent(identity.apply(item), item);
            }
            if (merged.size() > OffsetPagination.MAX_ITEMS) throw new IllegalArgumentException("Too many collection items");
            Snapshot<T> snapshot = new Snapshot<>(new ArrayList<>(merged.values()), page.nextOffset(), page.complete());
            synchronized (this) {
                requireCurrent(key, active, ticket);
                active.snapshot = snapshot;
            }
            requireCurrent(key, active, ticket);
            publish.accept(snapshot);
            requireCurrent(key, active, ticket);
        }, () -> isCurrent(key, active, ticket), OffsetPagination.MAX_PAGES);
        synchronized (this) {
            requireCurrent(key, active, ticket);
            return active.snapshot;
        }
    }

    private synchronized boolean isCurrent(K key, Entry<T> entry, Object ticket) {
        return entries.get(key) == entry && entry.request == ticket;
    }
    private void requireCurrent(K key, Entry<T> entry, Object ticket) {
        if (!isCurrent(key, entry, ticket)) throw new CancellationException("Collection load was superseded");
    }
}
