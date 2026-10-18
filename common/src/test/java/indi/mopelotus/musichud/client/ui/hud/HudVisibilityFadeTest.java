package indi.mopelotus.musichud.client.ui.hud;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HudVisibilityFadeTest {
    @Test void visibilitySourcesConvergeWithoutPreviewOverridingDisabledSettings() {
        assertEquals(0f, HudVisibilityFade.target(false, true, true, false, false));
        assertEquals(0f, HudVisibilityFade.target(true, false, true, false, false));
        assertEquals(0f, HudVisibilityFade.target(true, true, false, true, true));
        assertEquals(1f, HudVisibilityFade.target(true, true, true, true, true));
        assertEquals(1f, HudVisibilityFade.target(true, true, false, false, true));
        assertEquals(1f, HudVisibilityFade.target(true, true, false, true, false));
    }

    @Test void hiddenHudCanStartFadingInAndStopsExactlyAtTarget() {
        var fade = new HudVisibilityFade();
        fade.snap(0f);
        assertTrue(fade.hidden());
        assertEquals(0f, fade.advance(1f, 10));
        assertFalse(fade.hidden());
        assertEquals(.5f, fade.advance(1f, 150_000_010), .00001f);
        assertEquals(1f, fade.advance(1f, 300_000_010));
        assertEquals(1f, fade.advance(1f, Long.MAX_VALUE));
    }

    @Test void interruptedFadeRemainsContinuousAndDoesNotClearOutgoingContent() {
        var fade = new HudVisibilityFade();
        fade.snap(1f);
        fade.advance(0f, 0);
        float interrupted = fade.advance(1f, 150_000_000);
        assertEquals(.5f, interrupted, .00001f);
        assertFalse(fade.hidden());
        assertEquals(.75f, fade.advance(1f, 300_000_000), .00001f);
        assertEquals(1f, fade.advance(1f, 450_000_000));
        assertFalse(fade.hidden());
    }

    @Test void deferredClearIsOnlyReadyAfterFadeOutCompletes() {
        var fade = new HudVisibilityFade();
        fade.snap(1f);
        fade.advance(0f, 0);
        assertFalse(fade.hidden());
        fade.advance(0f, 299_999_999);
        assertFalse(fade.hidden());
        assertEquals(0f, fade.advance(0f, 300_000_000));
        assertTrue(fade.hidden());
    }

    @Test void monotonicClockMayWrapWithoutLosingElapsedTime() {
        var fade = new HudVisibilityFade();
        fade.snap(0f);
        long start = Long.MAX_VALUE - 100_000_000;
        fade.advance(1f, start);
        assertEquals(.5f, fade.advance(1f, start + 150_000_000), .00001f);
        assertEquals(1f, fade.advance(1f, start + 300_000_000));
    }
}
