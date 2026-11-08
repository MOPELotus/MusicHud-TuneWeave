package indi.mopelotus.musichud.utils.collections;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CancellationException;
import static org.junit.jupiter.api.Assertions.*;

class ItemMappingTest {
    private static final class Malformed extends RuntimeException implements ItemMapping.MalformedItem {}
    @Test void badDataDoesNotLoseValidNeighborsOrOrder() {
        assertEquals(List.of(1, 2, 1), ItemMapping.map("numbers", List.of("1", "bad", "2", "1"), Integer::valueOf));
        assertEquals(List.of(1, 3), ItemMapping.map("marked data", List.of(1, 2, 3), value -> {
            if (value == 2) throw new Malformed();
            return value;
        }));
    }
    @Test void cancellationAndOperationalErrorsAreNeverDiscardedAsBadItems() {
        var cancelled = new CancellationException("retired");
        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> ItemMapping.map("items", List.of(1), value -> { throw cancelled; })));
        var operational = new RuntimeException("operational failure");
        assertSame(operational, assertThrows(RuntimeException.class,
                () -> ItemMapping.map("items", List.of(1), value -> { throw operational; })));
    }
}
