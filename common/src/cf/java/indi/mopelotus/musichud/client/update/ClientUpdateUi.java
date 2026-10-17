package indi.mopelotus.musichud.client.update;

import icyllis.modernui.view.View;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import net.minecraft.client.resources.language.I18n;
import java.net.URI;
import java.nio.file.*;

/** Version notifications and a project-home link; no download or installation actions. */
public final class ClientUpdateUi implements AutoCloseable {
    public static final URI PROJECT_HOME = URI.create("https://github.com/MOPELotus/MusicHud-TuneWeave");
    private static String dismissedVersion;
    private static ClientUpdateUi active;
    private final View owner;
    private final ClientUpdateService service;
    private final Path notice = MusicHud.getConfigDirectory().resolve("musichud-tuneweave/cf-notice-v2.accepted");
    private AutoCloseable subscription;
    private boolean closed, manual, started;
    private Modal modal;
    private TextView progress;

    private ClientUpdateUi(View owner) { this.owner = owner; service = ClientUpdateService.INSTANCE; }
    public static ClientUpdateUi attach(View owner) {
        var ui = new ClientUpdateUi(owner); active = ui;
        if (ui.service != null) ui.subscription = ui.service.subscribe(state -> owner.post(() -> ui.render(state)));
        owner.post(ui::start);
        return ui;
    }
    private void start() {
        if (closed) return;
        started = true;
        if (Files.isRegularFile(notice, LinkOption.NOFOLLOW_LINKS)) { if (service != null) render(service.state()); return; }
        modal = new Modal(owner.getContext(), text(tr("cfTitle")), content(tr("cfNotice")),
                new Modal.ActionButton(tr("goLook"), (b, m) -> openProject()),
                new Modal.ActionButton(tr("understood"), (b, m) -> {
                    try { Files.createDirectories(notice.getParent()); Files.writeString(notice, "1\n", StandardOpenOption.CREATE_NEW); }
                    catch (java.io.IOException error) { MusicHud.LOGGER.warn("Could not save CF notice acknowledgement"); }
                    m.dismiss();
                }));
        modal.setOnDismissListener(() -> { modal = null; if (!closed && service != null) render(service.state()); });
        modal.show();
    }
    public static void manualCheck() {
        var ui = active;
        if (ui == null || ui.closed) return;
        ui.manual = true;
        if (ui.service == null) { ui.message(tr("cfUnavailable")); return; }
        if (ui.service.state().status() == ClientUpdateService.Status.AVAILABLE) ui.render(ui.service.state());
        else ui.service.check();
    }
    private void render(ClientUpdateService.State state) {
        if (closed || !started || service == null || service.state() != state) return;
        if (progress != null && modal != null && modal.isShowing()) {
            switch (state.status()) {
                case CURRENT -> { progress.setText(tr("current")); progress = null; manual = false; }
                case FAILED -> { progress.setText(tr("cfFailed")); progress = null; manual = false; }
                case AVAILABLE -> modal.dismiss();
                default -> { }
            }
            return;
        }
        if (modal != null && modal.isShowing()) return;
        switch (state.status()) {
            case AVAILABLE -> {
                String version = state.offer().version();
                if (!manual && version.equals(dismissedVersion)) return;
                manual = false; dismissedVersion = version;
                modal = new Modal(owner.getContext(), text(tr("cfAvailableTitle")),
                        content(tr("available", version) + "\n\n" + tr("cfUpdateNotice")),
                        new Modal.ActionButton(tr("goLook"), (b, m) -> { openProject(); m.dismiss(); }),
                        new Modal.ActionButton(tr("later"), (b, m) -> m.dismiss()));
                modal.setOnDismissListener(() -> modal = null);
                modal.show();
            }
            case CHECKING -> { if (manual) showProgress(); }
            case CURRENT -> { if (manual) { manual = false; message(tr("current")); } }
            case FAILED -> { if (manual) { manual = false; message(tr("cfFailed")); } }
            default -> { }
        }
    }
    private void showProgress() {
        progress = text(tr("checking"));
        modal = new Modal(owner.getContext(), progress, new Modal.ActionButton(tr("understood"), (b, m) -> m.dismiss()));
        modal.setOnDismissListener(() -> {
            modal = null; progress = null;
            if (!closed && service.state().status() == ClientUpdateService.Status.AVAILABLE) render(service.state());
        });
        modal.show();
    }
    private void message(String value) {
        if (closed) return;
        modal = new Modal(owner.getContext(), text(value), new Modal.ActionButton(tr("understood"), (b, m) -> m.dismiss()));
        modal.setOnDismissListener(() -> modal = null); modal.show();
    }
    private View content(String value) {
        var scroll = new icyllis.modernui.widget.ScrollView(owner.getContext()) {
            @Override protected void onMeasure(int width, int height) {
                super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.max(dp(48), owner.getHeight() - dp(180)), MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(text(value)); return scroll;
    }
    private TextView text(String value) {
        var text = new TextView(owner.getContext()); text.setText(value); text.setTextSize(Theme.TEXT_SIZE_NORMAL); text.setTextColor(Theme.NORMAL_TEXT_COLOR); return text;
    }
    public static String tr(String key, Object... args) { return I18n.get(MusicHud.MOD_ID + ".update." + key, args); }
    private static void openProject() { net.minecraft.Util.getPlatform().openUri(PROJECT_HOME); }
    @Override public void close() {
        closed = true; if (active == this) active = null;
        if (subscription != null) try { subscription.close(); } catch (Exception ignored) { }
        if (modal != null) { modal.setOnDismissListener(() -> {}); modal.dismiss(); modal = null; }
        progress = null;
    }
}
