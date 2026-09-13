package indi.mopelotus.musichud.client.ui.pages.search;

import java.util.*;
import java.util.function.Function;

/** Copies immutable service pages and appends only new stable identities. UI-thread confined. */
public final class SearchResultBuffer<T> {
    private final Function<T, String> identity;
    private LinkedHashMap<String, T> items = new LinkedHashMap<>();
    public SearchResultBuffer(Function<T, String> identity) { this.identity = identity; }
    public List<T> snapshot() { return List.copyOf(items.values()); }
    public void replace(List<T> page) { merge(page, true); }
    public List<T> append(List<T> page) { return merge(page, false); }
    private List<T> merge(List<T> page, boolean replace) {
        var next = replace ? new LinkedHashMap<String, T>() : new LinkedHashMap<>(items);
        List<T> added = new ArrayList<>();
        if (page != null) for (T item : page) {
            String key = identity.apply(Objects.requireNonNull(item));
            if (key == null || key.isBlank()) throw new IllegalArgumentException("Missing search result identity");
            if (next.putIfAbsent(key, item) == null) added.add(item);
            if (next.size() > 100_000) throw new IllegalArgumentException("Too many search results");
        }
        items = next; return List.copyOf(added);
    }
}
