package indi.mopelotus.musichud.companion.desktop;

import indi.mopelotus.musichud.companion.protocol.CompanionWire;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DisplayStateTest {
    private CompanionWire.Message message(CompanionWire.Kind kind, long generation, long seq, boolean locked) {
        boolean frame = kind == CompanionWire.Kind.FRAME;
        return new CompanionWire.Message(kind, generation, seq, 0, locked, frame ? 1 : 0, frame ? 1 : 0, new byte[frame ? 4 : 0]);
    }
    @Test void rapidLockAndClearNeverReintroducesOldFrame() {
        var frame = message(CompanionWire.Kind.FRAME, 1, 1, false);
        var state = DisplayState.empty().accept(frame, 1);
        state = state.accept(message(CompanionWire.Kind.STATUS, 1, 2, true), 2);
        assertTrue(state.locked());
        assertSame(frame, state.frame());
        state = state.accept(message(CompanionWire.Kind.CLEAR, 1, 3, true), 3);
        assertNull(state.frame());
        assertSame(state, state.accept(frame, 4));
        assertSame(state, state.accept(message(CompanionWire.Kind.FRAME, 2, 4, false), 5));
    }
    @Test void disconnectedAndExpiredStatesAreLockedOrHidden() {
        assertTrue(DisplayState.empty().locked());
        var state = DisplayState.empty().accept(message(CompanionWire.Kind.FRAME, 1, 1, false), 10);
        assertFalse(state.expired(11));
        assertTrue(state.expired(2_000_000_011L));
    }
}
