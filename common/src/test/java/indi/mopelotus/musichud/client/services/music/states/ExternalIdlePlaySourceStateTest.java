package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.user.Profile;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalIdlePlaySourceStateTest {
    @Test void physicalResetRemovesEveryVisibleSourceAndPublishesAfterClearingState() {
        var state = new ExternalIdlePlaySourceState();
        state.add(playlist(1)); state.add(playlist(2));
        Set<Long> visible = new HashSet<>(Set.of(1L, 2L)), changed = new HashSet<>();
        state.onChange(source -> {
            assertTrue(state.getSources().isEmpty(), "Listeners must see the cleared external snapshot");
            changed.add(source.getId());
        });
        state.onRemove(source -> assertTrue(visible.remove(source.getId()), "Each visible tile is removed once"));
        state.reset(); state.reset();
        assertTrue(state.getSources().isEmpty()); assertTrue(visible.isEmpty());
        assertEquals(Set.of(1L, 2L), changed);
    }

    @Test void detachedViewDoesNotReceiveLaterResetOrReplacementWorldEvents() {
        var state = new ExternalIdlePlaySourceState();
        List<Long> visible = new ArrayList<>();
        var add = state.onAdd(source -> visible.add(source.getId()));
        var remove = state.onRemove(source -> visible.remove(source.getId()));
        state.add(playlist(1));
        add.unregister(); remove.unregister();
        state.reset(); state.add(playlist(2)); state.reset();
        assertEquals(List.of(1L), visible, "Closed view listeners must stay detached across world replacement");
        assertTrue(state.getSources().isEmpty());
    }

    private static Playlist playlist(long id) {
        return Playlist.fromTuneWeave(id, "fixture:playlist:" + id, "Source " + id, "", 0, 0, Profile.ANONYMOUS);
    }
}
