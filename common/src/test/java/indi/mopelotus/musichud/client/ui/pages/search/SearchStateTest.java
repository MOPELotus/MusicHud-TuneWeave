package indi.mopelotus.musichud.client.ui.pages.search;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class SearchStateTest {
    @Test void replacingKeywordOrPlatformRejectsOldReplyWhileOtherTabsRemainIndependent() {
        var account = new AtomicReference<Object>(new Object());
        var gate = new SearchRequestGate<String>(account::get);
        var old = gate.begin("music"); var album = gate.begin("album"); var current = gate.begin("music");
        assertFalse(gate.isCurrent(old)); assertTrue(gate.isCurrent(album)); assertTrue(gate.isCurrent(current));
        account.set(new Object()); assertFalse(gate.isCurrent(current)); assertFalse(gate.isCurrent(album));
        var next = gate.begin("music"); gate.clear(); assertFalse(gate.isCurrent(next));
    }
    @Test void immutablePagesAppendByStableIdentityWithoutMutatingPriorSnapshot() {
        record Item(String ref, String name) {}
        var buffer = new SearchResultBuffer<Item>(Item::ref);
        var first = new Item("a", "First"); buffer.replace(List.of(first));
        var snapshot = buffer.snapshot();
        assertEquals(List.of(new Item("b", "Second")), buffer.append(List.of(new Item("a", "Changed"), new Item("b", "Second"))));
        assertEquals(List.of(first), snapshot); assertEquals(2, buffer.snapshot().size());
        assertThrows(IllegalArgumentException.class, () -> buffer.append(List.of(new Item("c", "Third"), new Item("", "Bad"))));
        assertEquals(2, buffer.snapshot().size(), "Invalid page must not partially append");
        buffer.replace(null); assertTrue(buffer.snapshot().isEmpty());
    }
}
