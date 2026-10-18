package indi.mopelotus.musichud.client.update;

import icyllis.modernui.view.View;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import net.minecraft.client.resources.language.I18n;
import java.nio.file.*;

/** UI-scoped subscription: a late result cannot reopen a destroyed fragment. */
public final class ClientUpdateUi implements AutoCloseable {
    private static String dismissedVersion;
    private final View owner;
    private final ClientUpdateService service;
    private AutoCloseable subscription;
    private boolean closed, manual, started;
    private AutoCloseable apiDownloadUi;
    private Modal modal;
    private ServiceUpdateUi serviceUi;
    private TextView progressText;
    private ClientUpdateService.State lastReadyShown;
    private final Path notice = MusicHud.getConfigDirectory().resolve("musichud-tuneweave/standard-service-notice-v1.accepted");
    private static ClientUpdateUi active;
    private ClientUpdateUi(View owner) { this.owner = owner; this.service = ClientUpdateService.INSTANCE; }
    public static ClientUpdateUi attach(View owner) {
        var ui = new ClientUpdateUi(owner); active = ui;
        ui.serviceUi = new ServiceUpdateUi(owner, () -> ui.started && ui.modal == null && ui.apiDownloadUi == null, () -> { if (ui.service != null) ui.render(ui.service.state()); });
        if (ui.service != null) ui.subscription = ui.service.subscribe(state -> owner.post(() -> ui.render(state)));
        owner.post(ui::start);
        return ui;
    }
    private void start() {
        if (closed) return;
        started = true;
        if (Files.isRegularFile(notice, LinkOption.NOFOLLOW_LINKS)) {
            if (service != null) render(service.state());
            return;
        }
        boolean[] download = {false};
        boolean[] chosen = {false};
        modal = new Modal(owner.getContext(), text(tr("serviceTitle")), content(tr("serviceNotice")),
                new Modal.ActionButton(tr("understood"), (b, m) -> {
                    if (closed || chosen[0]) return;
                    chosen[0] = true; acknowledge(); m.dismiss();
                }),
                new Modal.ActionButton(tr("goDownload"), (b, m) -> {
                    if (closed || chosen[0]) return;
                    chosen[0] = true; acknowledge(); download[0] = true; m.dismiss();
                }));
        modal.setOnDismissListener(() -> {
            modal = null;
            if (closed) return;
            if (download[0]) {
                apiDownloadUi = indi.mopelotus.musichud.client.ui.pages.ApiDistributionUi.openAndStart(owner, () -> {
                    apiDownloadUi = null;
                    if (!closed && service != null) render(service.state());
                });
            } else if (service != null) render(service.state());
        });
        modal.show();
    }
    private void acknowledge() {
        try { Files.createDirectories(notice.getParent()); Files.writeString(notice, "1\n", StandardOpenOption.CREATE_NEW); }
        catch (FileAlreadyExistsException accepted) { }
        catch (java.io.IOException error) { MusicHud.LOGGER.warn("Could not save service notice acknowledgement"); }
    }
    public static void manualServiceCheck() { ServiceUpdateUi.manualCheck(); }
    public static void manualCheck() {
        if (active == null || active.closed) return;
        active.manual = true;
        if (active.service == null) { active.showMessage(tr("unavailable")); return; }
        if (active.service.state().status() == ClientUpdateService.Status.READY) active.render(active.service.state());
        else active.service.check();
    }
    private void render(ClientUpdateService.State state) {
        if (closed || !started || apiDownloadUi != null || service == null || service.state() != state || serviceUi.isShowing()) return;
        if (progressText != null && modal != null && modal.isShowing()) {
            switch (state.status()) {
                case READY -> { progressText.setText(tr("ready")); progressText = null; manual = false; lastReadyShown = state; }
                case FAILED -> { progressText.setText(tr("failed")); progressText = null; manual = false; }
                case CURRENT -> { progressText.setText(tr("current")); progressText = null; manual = false; }
                case AVAILABLE -> modal.dismiss();
                default -> { }
            }
            return;
        }
        if (modal != null && modal.isShowing()) return;
        switch (state.status()) {
            case AVAILABLE -> {
                String version = state.offer().version();
                if (!manual && version.equals(dismissedVersion)) { serviceUi.render(); return; }
                manual = false; dismissedVersion = version;
                boolean blocked = !state.message().isEmpty();
                String message = tr("available", version) + "\n\n" + (blocked ? tr("service.modCompatibilityBlocked") : tr("restartNotice"));
                var source = DownloadSourceUi.create(owner.getContext());
                View body = blocked ? content(message) : DownloadSourceUi.content(owner, text(message), source);
                var now = new Modal.ActionButton(tr(blocked ? "service.manualPairAction" : "now"), (b, m) -> {
                    if (blocked) com.mojang.blaze3d.Blaze3D.openUri(state.offer().releasePageUri());
                    else service.download(DownloadSourceUi.selected(source));
                    m.dismiss();
                });
                var later = new Modal.ActionButton(tr("later"), (b, m) -> m.dismiss());
                modal = new Modal(owner.getContext(), text(tr("title")), body, now, later);
                modal.setOnDismissListener(() -> { modal = null; if (!closed) { if (service.state().status() != ClientUpdateService.Status.AVAILABLE) render(service.state()); else serviceUi.render(); } });
                modal.show();
            }
            case CHECKING -> { if (manual) showProgress(tr("checking")); }
            case DOWNLOADING -> showProgress(tr("downloading"));
            case READY -> { if (lastReadyShown != state) { lastReadyShown = state; showMessage(tr("ready")); } }
            case FAILED -> { if (manual || state.message().equals("download")) { manual = false; showMessage(tr("failed")); } }
            case CURRENT -> { if (manual) { manual = false; showMessage(tr("current")); } }
            default -> { }
        }
        serviceUi.render();
    }
    private void showProgress(String message) {
        progressText = text(message);
        modal = new Modal(owner.getContext(), progressText, new Modal.ActionButton(tr("understood"), (b, m) -> m.dismiss()));
        modal.setOnDismissListener(() -> {
            modal = null; progressText = null;
            if (!closed) { if (service.state().status() == ClientUpdateService.Status.AVAILABLE) render(service.state()); else serviceUi.render(); }
        });
        modal.show();
    }
    private void showMessage(String message) {
        if (closed) return;
        modal = new Modal(owner.getContext(), text(message), new Modal.ActionButton(tr("understood"), (b, m) -> m.dismiss()));
        modal.setOnDismissListener(() -> { modal = null; if (!closed) serviceUi.render(); }); modal.show();
    }
    private View content(String value) {
        var scroll = new icyllis.modernui.widget.ScrollView(owner.getContext()) {
            @Override protected void onMeasure(int width, int height) {
                int limit = Math.max(dp(48), owner.getHeight() - dp(180));
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(text(value));
        return scroll;
    }
    private TextView text(String value) {
        var view = new TextView(owner.getContext()); view.setText(value); view.setTextSize(Theme.TEXT_SIZE_NORMAL); view.setTextColor(Theme.NORMAL_TEXT_COLOR); return view;
    }
    public static String tr(String key, Object... args) { return I18n.get(MusicHud.MOD_ID + ".update." + key, args); }
    @Override public void close() {
        closed = true;
        if (active == this) active = null;
        if (apiDownloadUi != null) { try { apiDownloadUi.close(); } catch (Exception ignored) { } apiDownloadUi = null; }
        if (serviceUi != null) serviceUi.close();
        if (subscription != null) try { subscription.close(); } catch (Exception ignored) { }
        if (modal != null) { modal.setOnDismissListener(() -> {}); modal.dismiss(); modal = null; }
    }
}
