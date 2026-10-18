package indi.mopelotus.musichud.client.update;

import icyllis.modernui.view.View;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import java.util.function.BooleanSupplier;

/** Attached to the same UI lifetime as the mod prompt, and queued behind it. */
final class ServiceUpdateUi implements AutoCloseable {
    private static ServiceUpdateUi active;
    private static String dismissedVersion;
    private final View owner;
    private final BooleanSupplier canShow;
    private final Runnable onDismiss;
    private final ServiceUpdateService service = ServiceUpdateService.getInstance();
    private AutoCloseable subscription;
    private boolean closed, manual;
    private Modal modal;
    private TextView progress;
    private ServiceUpdateService.State lastMessage;
    private ServiceUpdateService.State hiddenPending;
    private ServiceUpdateService.State pendingPresentation;
    ServiceUpdateUi(View owner, BooleanSupplier canShow, Runnable onDismiss) {
        this.owner = owner; this.canShow = canShow; this.onDismiss = onDismiss; active = this;
        subscription = service.subscribe(state -> owner.post(() -> render(state)));
    }
    static void manualCheck() {
        if (active == null || active.closed) return;
        active.manual = true;
        active.hiddenPending = null;
        active.service.check();
        active.render();
    }
    boolean isShowing() { return modal != null && modal.isShowing(); }
    void render() { render(service.state()); }
    private void render(ServiceUpdateService.State state) {
        if (closed || service.state() != state || hiddenPending == state) return;
        if (progress != null && modal != null && modal.isShowing()) {
            if (state.status() == ServiceUpdateService.Status.CHECKING || state.status() == ServiceUpdateService.Status.DOWNLOADING)
                pendingPresentation = state;
            if (state.status() == ServiceUpdateService.Status.AVAILABLE) { modal.dismiss(); return; }
            if (state.status() != ServiceUpdateService.Status.CHECKING && state.status() != ServiceUpdateService.Status.DOWNLOADING) {
                progress.setText(message(state)); progress = null; manual = false; lastMessage = state;
            }
            return;
        }
        if (modal != null || !canShow.getAsBoolean()) return;
        switch (state.status()) {
            case AVAILABLE -> {
                String version = state.offer().targetVersion();
                if (!manual && version.equals(dismissedVersion)) return;
                manual = false; dismissedVersion = version;
                var source = DownloadSourceUi.create(owner.getContext());
                var body = DownloadSourceUi.content(owner, text(tr("available", state.offer().currentVersion(), version)), source);
                modal = new Modal(owner.getContext(), text(tr("title")), body,
                        new Modal.ActionButton(ClientUpdateUi.tr("now"), (button, dialog) -> { service.download(DownloadSourceUi.selected(source)); dialog.dismiss(); }),
                        new Modal.ActionButton(ClientUpdateUi.tr("later"), (button, dialog) -> dialog.dismiss()));
                modal.setOnDismissListener(() -> { modal = null; if (!closed) { onDismiss.run(); render(); } });
                modal.show();
            }
            case CHECKING -> { if (manual) show(message(state), true); }
            case DOWNLOADING -> show(message(state), true);
            case READY -> { if (lastMessage != state) { lastMessage = state; show(message(state), false); } }
            case FAILED -> { if (lastMessage != state && (manual || "download".equals(state.message()))) { lastMessage = state; manual = false; show(message(state), false); } }
            case CURRENT, EXTERNAL, UNKNOWN, INCOMPATIBLE -> { if (manual) { manual = false; show(message(state), false); } }
            default -> {}
        }
    }
    private String message(ServiceUpdateService.State state) {
        return switch (state.status()) {
            case CHECKING -> ClientUpdateUi.tr("checking");
            case DOWNLOADING -> tr("downloading");
            case READY -> tr("nextStart".equals(state.message()) ? "nextStart" : "ready");
            case CURRENT -> tr("current");
            case EXTERNAL -> tr("portUnavailable".equals(state.message()) ? "portUnavailable" : "external");
            case UNKNOWN -> tr("unknown");
            case INCOMPATIBLE -> tr("incompatible");
            default -> tr("failed");
        };
    }
    private void show(String message, boolean pending) {
        pendingPresentation = pending ? service.state() : null;
        var body = text(message); progress = pending ? body : null;
        modal = new Modal(owner.getContext(), text(tr("title")), body,
                new Modal.ActionButton(ClientUpdateUi.tr("understood"), (button, dialog) -> dialog.dismiss()));
        modal.setOnDismissListener(() -> {
            if (pending && service.state() == pendingPresentation) hiddenPending = pendingPresentation;
            modal = null; progress = null; pendingPresentation = null;
            if (!closed) { onDismiss.run(); render(); }
        });
        modal.show();
    }
    private TextView text(String message) {
        var result = new TextView(owner.getContext()); result.setText(message);
        result.setTextSize(Theme.TEXT_SIZE_NORMAL); result.setTextColor(Theme.NORMAL_TEXT_COLOR); return result;
    }
    private static String tr(String key, Object... args) { return ClientUpdateUi.tr("service." + key, args); }
    @Override public void close() {
        closed = true;
        if (active == this) active = null;
        try { subscription.close(); } catch (Exception ignored) {}
        if (modal != null) { modal.setOnDismissListener(() -> {}); modal.dismiss(); modal = null; }
    }
}
