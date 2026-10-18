package indi.mopelotus.musichud.client.ui.hud.renderer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkinTransitionTest {
    private static final long DURATION = 400_000_000L;

    @Test
    void firstSkinFadesInAndNullFadesOut() {
        SkinTransition<String> fade = new SkinTransition<>(DURATION);
        assertFalse(fade.isVisible());
        assertEquals(0, alpha(fade.sample("a", 0), "a"));
        assertEquals(.5f, alpha(fade.sample("a", DURATION / 2), "a"), 1e-6);
        assertEquals(1, alpha(fade.sample("a", DURATION), "a"));
        assertEquals(1, alpha(fade.sample(null, DURATION), "a"));
        assertTrue(fade.isVisible());
        assertEquals(.5f, alpha(fade.sample(null, DURATION * 3 / 2), "a"), 1e-6);
        assertTrue(fade.sample(null, DURATION * 2).isEmpty());
        assertFalse(fade.isVisible());
    }

    @Test
    void interruptedFadePreservesEveryDisplayedAlpha() {
        SkinTransition<String> fade = settled();
        fade.sample("b", DURATION);
        var before = fade.sample("b", DURATION + DURATION / 3);
        var after = fade.sample("c", DURATION + DURATION / 3);
        assertEquals(alpha(before, "a"), alpha(after, "a"));
        assertEquals(alpha(before, "b"), alpha(after, "b"));
        assertEquals(0, alpha(after, "c"));
        var middle = fade.sample("c", DURATION + DURATION / 3 + DURATION / 2);
        assertEquals(1, total(middle), 1e-6);
        assertEquals(.5f, alpha(middle, "c"), 1e-6);
    }

    @Test
    void returningToExistingSkinKeepsItsCurrentAlphaAndDoesNotDuplicate() {
        SkinTransition<String> fade = settled();
        fade.sample("b", DURATION);
        var halfway = fade.sample("b", DURATION * 3 / 2);
        var reversed = fade.sample("a", DURATION * 3 / 2);
        assertEquals(halfway, reversed);
        assertEquals(2, reversed.size());
        assertEquals(List.of(new SkinTransition.Layer<>("a", 1f)),
                fade.sample("a", DURATION * 5 / 2));
    }

    @Test
    void sameTargetDoesNotRestartTransition() {
        SkinTransition<String> fade = new SkinTransition<>(DURATION);
        fade.sample("a", 0);
        for (int i = 1; i <= 20; i++) fade.sample("a", DURATION * i / 20);
        assertEquals(1, alpha(fade.sample("a", DURATION), "a"));
    }

    @Test
    void fadeOutCanBeInterruptedWithoutOpacityJump() {
        SkinTransition<String> fade = settled();
        fade.sample(null, DURATION);
        var fading = fade.sample(null, DURATION * 3 / 2);
        var resumed = fade.sample("b", DURATION * 3 / 2);
        assertEquals(total(fading), total(resumed));
        assertEquals(.5f, alpha(resumed, "a"), 1e-6);
        assertEquals(.75f, total(fade.sample("b", DURATION * 2)), 1e-6);
    }

    @Test
    void regressingClockCannotRewindOrRestartFade() {
        SkinTransition<String> fade = settled();
        fade.sample("b", DURATION);
        var before = fade.sample("b", DURATION * 3 / 2);
        assertEquals(before, fade.sample("b", DURATION));
        assertEquals(before, fade.sample("a", DURATION));
    }

    @Test
    void monotonicCounterWrapIsSafe() {
        SkinTransition<String> fade = new SkinTransition<>(DURATION);
        long start = Long.MAX_VALUE - DURATION / 2;
        fade.sample("a", start);
        assertEquals(.5f, alpha(fade.sample("a", start + DURATION / 2), "a"), 1e-6);
        assertEquals(1, alpha(fade.sample("a", start + DURATION), "a"));
    }

    @Test
    void pathologicalChurnStaysBoundedAndRetainsTotalAlpha() {
        SkinTransition<String> fade = settled();
        for (int i = 0; i < 10_000; i++) {
            var layers = fade.sample("skin" + i, DURATION + i * 1_000_000L);
            assertTrue(layers.size() <= SkinTransition.MAX_LAYERS);
            assertEquals(1, total(layers), 1e-5);
            assertEquals(layers.size(), layers.stream().map(SkinTransition.Layer::skin).distinct().count());
            for (var layer : layers) assertTrue(layer.alpha() >= 0 && layer.alpha() <= 1.00001f);
        }
        assertEquals(List.of(new SkinTransition.Layer<>("skin9999", 1f)),
                fade.sample("skin9999", DURATION * 2 + 9_999 * 1_000_000L));
    }

    @Test
    void snapshotsAreImmutableAndUnaffectedByFutureChanges() {
        SkinTransition<String> fade = settled();
        var snapshot = fade.sample("b", DURATION);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        fade.sample("c", DURATION * 3 / 2);
        assertEquals(List.of(new SkinTransition.Layer<>("a", 1f), new SkinTransition.Layer<>("b", 0f)), snapshot);
    }

    @Test
    void rejectsNonpositiveDuration() {
        assertThrows(IllegalArgumentException.class, () -> new SkinTransition<>(0));
        assertThrows(IllegalArgumentException.class, () -> new SkinTransition<>(-1));
    }

    private static SkinTransition<String> settled() {
        SkinTransition<String> fade = new SkinTransition<>(DURATION);
        fade.sample("a", 0);
        fade.sample("a", DURATION);
        return fade;
    }

    private static float alpha(List<SkinTransition.Layer<String>> layers, String skin) {
        return layers.stream().filter(layer -> layer.skin().equals(skin))
                .map(SkinTransition.Layer::alpha).findFirst().orElse(0f);
    }

    private static float total(List<SkinTransition.Layer<String>> layers) {
        float sum = 0;
        for (var layer : layers) sum += layer.alpha();
        return sum;
    }
}
