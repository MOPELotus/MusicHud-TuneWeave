package indi.mopelotus.musichud.utils.collections;

import java.util.List;
import java.util.concurrent.CancellationException;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemMappingTest {
    @Test void malformedItemsDoNotLoseValidNeighborsOrderOrDuplicates() {
        assertEquals(List.of(1, 2, 1), ItemMapping.map("numbers", List.of("1", "bad", "2", "", "1"), value ->
                value.isEmpty() ? null : Integer.valueOf(value)));
    }
    @Test void cancellationTransportFailuresAndFatalErrorsNeverBecomeSkippedData() {
        var cancelled = new CancellationException("scope replaced");
        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> ItemMapping.map("items", List.of(1), value -> { throw cancelled; })));
        var transport = new TuneWeaveException("transport failed", true);
        assertSame(transport, assertThrows(TuneWeaveException.class,
                () -> ItemMapping.map("items", List.of(1), value -> { throw transport; })));
        var fatal = new AssertionError("invariant");
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> ItemMapping.map("items", List.of(1), value -> { throw fatal; })));
    }
}
