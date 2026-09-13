package indi.mopelotus.musichud.client.ui.pages.search;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class SearchRequestGate<K> {
    public record Ticket<K>(K key, Object account) {}
    private final ConcurrentHashMap<K, Ticket<K>> current = new ConcurrentHashMap<>();
    private final Supplier<Object> account;
    public SearchRequestGate(Supplier<Object> account) { this.account = account; }
    public Ticket<K> begin(K key) {
        var ticket = new Ticket<>(key, account.get()); current.put(key, ticket); return ticket;
    }
    public boolean isCurrent(Ticket<K> ticket) {
        return current.get(ticket.key()) == ticket && account.get() == ticket.account();
    }
    public void clear() { current.clear(); }
}
