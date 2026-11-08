package indi.mopelotus.musichud.utils.collections;

import com.google.gson.JsonParseException;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeaveApiClient.TuneWeaveException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Function;

/** Maps independent data items. Cancellation and request/lifecycle failures still propagate. */
public final class ItemMapping {
    private ItemMapping() {}

    public static <T> void forEach(String operation, Iterable<T> items, java.util.function.Consumer<T> consumer) {
        map(operation, items, item -> { consumer.accept(item); return null; });
    }

    public static <T, R> List<R> map(String operation, Iterable<T> items, Function<T, R> mapper) {
        List<R> result = new ArrayList<>();
        int skipped = 0;
        for (T item : items) {
            try {
                R value = mapper.apply(item);
                if (value != null) result.add(value);
            } catch (CancellationException cancelled) {
                throw cancelled;
            } catch (TuneWeaveException failure) {
                if (failure.getStatusCode() != 0 || failure.isRetryable()
                        || !"client_error".equals(failure.getCode())) throw failure;
                skipped++;
            } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException
                     | NullPointerException | ClassCastException | IndexOutOfBoundsException | JsonParseException invalid) {
                skipped++;
            }
        }
        if (skipped > 0) MusicHud.getLogger(ItemMapping.class).warn("Skipped {} invalid items while mapping {}", skipped, operation);
        return result;
    }
}
