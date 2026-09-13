package indi.mopelotus.musichud.client.services.tuneweave;

import java.util.List;

public record TuneWeaveSearchPage(List<?> items, int nextOffset, boolean hasMore) {
    public TuneWeaveSearchPage { items = List.copyOf(items); }
}
