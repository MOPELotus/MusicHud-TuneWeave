package indi.mopelotus.musichud.client.services.music;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FavoriteIntelligenceStateTest {
    @Test void oldLoadCannotBlockOrReleaseReplacementLoad() {
        var state = new FavoriteIntelligenceState();
        Object first = new Object(), second = new Object();
        long old = state.enable(first);
        assertTrue(state.beginLoad(old, first));
        assertFalse(state.beginLoad(old, first));
        long current = state.enable(second);
        assertFalse(state.isLoading());
        assertTrue(state.beginLoad(current, second));
        state.endLoad(old);
        assertTrue(state.isLoading());
        assertFalse(state.beginLoad(old, first));
        state.endLoad(current);
        assertFalse(state.isLoading());
    }

    @Test
    void oldLoadIsRejectedAfterDisableAndReplacement() {
        var state = new FavoriteIntelligenceState();
        Object oldPlaylist = new Object();
        long old = state.enable(oldPlaylist);
        state.disable();
        Object currentPlaylist = new Object();
        long current = state.enable(currentPlaylist);
        assertFalse(state.accepts(old, oldPlaylist));
        assertTrue(state.accepts(current, currentPlaylist));
    }

    @Test
    void samePlaylistRefreshSupersedesEarlierLoad() {
        var state = new FavoriteIntelligenceState();
        Object playlist = new Object();
        long first = state.enable(playlist);
        long second = state.enable(playlist);
        assertFalse(state.accepts(first, playlist));
        assertTrue(state.accepts(second, playlist));
    }

    @Test
    void disabledStateRejectsAllCompletions() {
        var state = new FavoriteIntelligenceState();
        Object playlist = new Object();
        long generation = state.enable(playlist);
        state.disable();
        assertFalse(state.accepts(generation, playlist));
    }
}
