package indi.mopelotus.musichud.client.companion;

/** Render-thread ownership of a temporary drawable binding on an existing GPU context. */
final class DesktopDrawableBinding<B> {
    interface Access<B> {
        long current(B backend);
        long utility(B backend);
        void bind(B backend, long window);
        default boolean isCurrent(B backend, long window) { return current(backend) == window; }
    }

    private final Access<B> access;
    private B owner;
    private long previousWindow;

    DesktopDrawableBinding(Access<B> access) { this.access = access; }

    boolean needsRestore() { return owner != null; }

    void update(B backend, boolean offscreen) {
        if (owner != null && (!offscreen || backend != owner)) restore();
        if (!offscreen || backend == null) return;
        if (owner == null) {
            previousWindow = access.current(backend);
            owner = backend;
        }
        try {
            long utility = access.utility(backend);
            if (access.current(backend) != utility || !access.isCurrent(backend, utility)) access.bind(backend, utility);
        } catch (RuntimeException | Error failure) {
            try { restore(); }
            catch (RuntimeException | Error restoration) { failure.addSuppressed(restoration); }
            throw failure;
        }
    }

    void restore() {
        if (owner == null) return;
        // Retain ownership on failure so the next frame/shutdown can retry restoration.
        access.bind(owner, previousWindow);
        owner = null;
        previousWindow = 0;
    }

    /** A render-frame guard can defer GPU work until the original binding is usable again. */
    boolean tryRestore() {
        try { restore(); return true; }
        catch (RuntimeException failure) { return false; }
    }
}
