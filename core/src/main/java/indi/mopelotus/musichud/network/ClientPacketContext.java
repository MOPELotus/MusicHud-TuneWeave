package indi.mopelotus.musichud.network;

import java.util.concurrent.Executor;

/** Local transport admission follows a decoded S2C message into its asynchronous state publication. */
public final class ClientPacketContext {
    public interface Admission {
        boolean isCurrent();
        void runIfCurrent(Runnable action);
    }
    private static final ThreadLocal<Admission> CURRENT = new ThreadLocal<>();
    private static final Admission REJECTED = new Admission() {
        public boolean isCurrent() { return false; }
        public void runIfCurrent(Runnable action) {}
    };
    private ClientPacketContext() {}

    public static Admission capture() {
        Admission admission = CURRENT.get();
        return admission == null ? REJECTED : admission;
    }

    public static void receive(Admission admission, Runnable receiver) {
        admission.runIfCurrent(() -> {
            Admission previous = CURRENT.get();
            CURRENT.set(admission);
            try { receiver.run(); }
            finally {
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            }
        });
    }

    public static void execute(Executor executor, Runnable mutation) {
        Admission admission = capture();
        if (admission.isCurrent()) executor.execute(() -> admission.runIfCurrent(mutation));
    }
}
