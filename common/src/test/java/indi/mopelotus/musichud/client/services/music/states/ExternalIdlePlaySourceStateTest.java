package indi.mopelotus.musichud.client.services.music.states;

import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.beans.user.Profile;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalIdlePlaySourceStateTest {
    @Test void replacingPusherReplacesTheTileAndLocalSourcesNeverDuplicate() {
        UUID self = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var state = new ExternalIdlePlaySourceState(() -> self);
        Playlist first = playlist(1).copyWithPusherInfo(new indi.mopelotus.musichud.beans.music.PusherInfo(a, "A"));
        Playlist next = playlist(1).copyWithPusherInfo(new indi.mopelotus.musichud.beans.music.PusherInfo(b, "B"));
        Playlist own = playlist(2).copyWithPusherInfo(new indi.mopelotus.musichud.beans.music.PusherInfo(self, "Self"));
        Playlist local = playlist(3);
        List<String> events = new ArrayList<>();
        state.onAdd(source -> events.add("+" + source.getPusherInfo().getPlayerName()));
        state.onRemove(source -> events.add("-" + source.getPusherInfo().getPlayerName()));
        state.updateAll(List.of(first, own, local), List.of());
        state.updateAll(List.of(next, own, local), List.of());
        assertEquals(List.of("+A", "-A", "+B"), events);
        assertEquals(Set.of(next), state.getSources());
        state.updateAll(List.of(own), List.of());
        assertTrue(state.getSources().isEmpty());
    }

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
