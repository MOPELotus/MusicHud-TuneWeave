package indi.mopelotus.musichud.client.companion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DesktopDrawableBindingTest {
    @Test void unavailableOriginalDrawableDefersFramesUntilRecoveryWithoutLosingOwnership() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        assertFalse(binding.needsRestore());
        binding.update(gl, true);
        assertTrue(binding.needsRestore());
        for (int frame = 0; frame < 20; frame++) {
            gl.failOnce.add(10L);
            assertFalse(binding.tryRestore());
            assertTrue(binding.needsRestore(), "A disconnected receiver must not stop frame-driven recovery");
        }
        assertTrue(binding.tryRestore());
        assertFalse(binding.needsRestore());
        assertEquals(10, gl.current);
        assertEquals(10, gl.nativeWindow);
        int calls = access.calls.size();
        assertTrue(binding.tryRestore());
        assertEquals(calls, access.calls.size());
    }

    private static final class Backend {
        final String name;
        final long utility;
        long current;
        long nativeWindow;
        boolean expectedContext = true;
        final Set<Long> failOnce = new HashSet<>();
        Backend(String name, long current, long utility) {
            this.name = name; this.current = current; this.nativeWindow = current; this.utility = utility;
        }
    }

    private static final class Access implements DesktopDrawableBinding.Access<Backend> {
        final List<String> calls = new ArrayList<>();
        @Override public long current(Backend backend) { return backend.current; }
        @Override public boolean isCurrent(Backend backend, long window) {
            return backend.expectedContext && backend.nativeWindow == window;
        }
        @Override public long utility(Backend backend) { return backend.utility; }
        @Override public void bind(Backend backend, long window) {
            calls.add(backend.name + ":" + window);
            backend.current = window;
            backend.nativeWindow = window;
            backend.expectedContext = true;
            // Exercise a failure after a native operation has already changed state.
            if (backend.failOnce.remove(window)) throw new IllegalStateException("bind " + window);
        }
    }

    @Test void repeatedMinimizedFramesRetainOriginalDrawableThenRestoreOnVisibility() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        binding.update(gl, true);
        binding.update(gl, true);
        assertEquals(List.of("gl:20"), access.calls);
        binding.update(gl, false);
        binding.update(gl, false);
        assertEquals(10, gl.current);
        assertEquals(List.of("gl:20", "gl:10"), access.calls);
    }

    @Test void disconnectOrNonGlBackendRestoresOwnedDrawable() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        binding.update(gl, true);
        binding.update(null, false);
        assertEquals(10, gl.current);
        binding.update(gl, true);
        binding.update(null, true);
        assertEquals(10, gl.current);
        assertEquals(List.of("gl:20", "gl:10", "gl:20", "gl:10"), access.calls);
    }

    @Test void backendSwitchRestoresOldBackendBeforeBindingTheNewOne() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var first = new Backend("first", 10, 20);
        var second = new Backend("second", 30, 40);
        binding.update(first, true);
        binding.update(second, true);
        binding.restore();
        assertEquals(10, first.current);
        assertEquals(30, second.current);
        assertEquals(List.of("first:20", "first:10", "second:40", "second:30"), access.calls);
    }

    @Test void externalDrawableChangeDoesNotReplaceTheSavedOriginal() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        binding.update(gl, true);
        gl.current = gl.nativeWindow = 99;
        binding.update(gl, true);
        binding.restore();
        assertEquals(10, gl.current);
        assertEquals(List.of("gl:20", "gl:20", "gl:10"), access.calls);
    }

    @Test void staleCacheIsReconciledWhenNativeDrawableAlreadyMatches() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        binding.update(gl, true);
        gl.current = 10; // nativeWindow remains the utility; simulate a stale backend cache.
        binding.update(gl, true);
        assertEquals(20, gl.current);
        binding.restore();
        assertEquals(10, gl.nativeWindow);
        assertEquals(List.of("gl:20", "gl:20", "gl:10"), access.calls);
    }

    @Test void cachedUtilityDoesNotHideAnExternallyChangedNativeContext() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        binding.update(gl, true);
        gl.expectedContext = false;
        binding.update(gl, true);
        binding.restore();
        assertTrue(gl.expectedContext);
        assertEquals(10, gl.current);
        assertEquals(List.of("gl:20", "gl:20", "gl:10"), access.calls);
    }

    @Test void partiallyAppliedEnterIsRolledBackAndCanBeRetried() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        gl.failOnce.add(20L);
        assertThrows(IllegalStateException.class, () -> binding.update(gl, true));
        assertEquals(10, gl.current);
        binding.update(gl, true);
        binding.restore();
        assertEquals(List.of("gl:20", "gl:10", "gl:20", "gl:10"), access.calls);
    }

    @Test void failedRestoreRetainsOwnershipAndDoesNotEnterAnotherBackend() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var first = new Backend("first", 10, 20);
        var second = new Backend("second", 30, 40);
        binding.update(first, true);
        first.failOnce.add(10L);
        assertThrows(IllegalStateException.class, () -> binding.update(second, true));
        assertEquals(30, second.current);
        binding.update(second, true);
        binding.restore();
        assertEquals(List.of("first:20", "first:10", "first:10", "second:40", "second:30"), access.calls);
    }

    @Test void enterAndRollbackFailureKeepsOriginalForLaterRecovery() {
        var access = new Access();
        var binding = new DesktopDrawableBinding<>(access);
        var gl = new Backend("gl", 10, 20);
        gl.failOnce.addAll(List.of(10L, 20L));
        var failure = assertThrows(IllegalStateException.class, () -> binding.update(gl, true));
        assertEquals(1, failure.getSuppressed().length);
        binding.restore();
        binding.restore();
        assertEquals(10, gl.current);
        assertEquals(List.of("gl:20", "gl:10", "gl:10"), access.calls);
    }
}
