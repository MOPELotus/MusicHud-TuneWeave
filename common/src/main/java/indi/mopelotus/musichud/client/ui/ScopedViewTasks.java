package indi.mopelotus.musichud.client.ui;

import java.util.concurrent.*;
import java.util.function.*;

/** Account and view-bound work. All lifecycle methods and callbacks run on the UI executor. */
public final class ScopedViewTasks {
    public interface Prepare { <T> Supplier<T> capture(Supplier<T> request); }
    public record Token(Object lifetime, Object account) {}
    private final Executor worker, ui;
    private final Supplier<Object> account;
    private final Prepare prepare;
    private final AsyncCollectionMutation mutations = new AsyncCollectionMutation();
    private volatile Token token;
    private volatile boolean active;
    private volatile Object readTicket;
    private boolean ready, failed;

    public ScopedViewTasks(Executor worker, Executor ui, Supplier<Object> account, Prepare prepare) {
        this.worker = worker; this.ui = ui; this.account = account; this.prepare = prepare;
    }
    public void attach() { active = true; token = new Token(new Object(), account.get()); ready = false; }
    public void detach() { active = false; token = null; ready = false; }
    public Token capture() { return token; }
    public boolean isCurrent(Token value) { return active && value != null && value == token && value.account() == account.get(); }
    public boolean isCurrent() { return isCurrent(token); }
    public boolean failed() { return failed; }
    public boolean canMutate() { return ready && isCurrent() && !mutations.isBusy(); }

    public <T> void load(Function<Consumer<T>, T> request, Consumer<T> render, Consumer<RuntimeException> error) {
        if (!active) return;
        Token ticket = token = new Token(new Object(), account.get());
        ready = false; failed = false;
        try {
            Supplier<T> prepared = prepare.capture(() -> request.apply(value -> {
                requireCurrent(ticket);
                ui.execute(() -> { if (isCurrent(ticket)) render.accept(value); });
            }));
            worker.execute(() -> {
                try {
                    requireCurrent(ticket);
                    T value = prepared.get();
                    ui.execute(() -> { if (isCurrent(ticket)) { ready = true; render.accept(value); } });
                } catch (RuntimeException problem) {
                    ui.execute(() -> { if (isCurrent(ticket)) { failed = true; error.accept(problem); } });
                }
            });
        } catch (RuntimeException problem) { failed = true; error.accept(problem); }
    }

    /** A details popup/read must not replace the main collection's generation or readiness. */
    public <T> void read(Supplier<T> request, Consumer<T> render, Consumer<RuntimeException> error) {
        Token ticket = token;
        if (!isCurrent(ticket)) return;
        Object read = readTicket = new Object();
        try {
            var prepared = prepare.capture(request);
            worker.execute(() -> {
                try {
                    requireCurrent(ticket); if (read != readTicket) return;
                    T value = prepared.get(); ui.execute(() -> { if (isCurrent(ticket) && read == readTicket) render.accept(value); });
                } catch (RuntimeException problem) { ui.execute(() -> { if (isCurrent(ticket) && read == readTicket) error.accept(problem); }); }
            });
        } catch (RuntimeException problem) { error.accept(problem); }
    }

    public boolean mutate(Token ticket, Runnable request, Runnable success, Consumer<RuntimeException> error) {
        if (!canMutate() || !isCurrent(ticket)) return false;
        try {
            Supplier<Boolean> prepared = prepare.capture(() -> { request.run(); return true; });
            return mutations.submit(worker, ui, () -> isCurrent(ticket), prepared, ignored -> success.run(), error, () -> {});
        } catch (RuntimeException problem) { error.accept(problem); return false; }
    }

    private void requireCurrent(Token value) {
        if (!isCurrent(value)) throw new CancellationException("View or account changed");
    }
}
