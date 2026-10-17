package indi.mopelotus.musichud.client.update;

import icyllis.modernui.view.View;
import icyllis.modernui.view.MeasureSpec;
import icyllis.modernui.widget.TextView;
import indi.mopelotus.musichud.BuildDistribution;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.Theme;
import indi.mopelotus.musichud.client.ui.components.Modal;
import net.minecraft.client.resources.language.I18n;
import java.net.URI;
import java.nio.file.*;

/** UI-scoped subscription: a late result cannot reopen a destroyed fragment. */
public final class ClientUpdateUi implements AutoCloseable {
    public static final URI FULL_EDITION = URI.create("https://github.com/MOPELotus/MusicHud-TuneWeave/releases");
    private static String dismissedVersion;
    private final View owner;
    private final ClientUpdateService service;
    private AutoCloseable subscription;
    private boolean closed, manual;
    private Modal modal;
    private TextView progressText;
    private final Path notice = MusicHud.getConfigDirectory().resolve("musichud-tuneweave/cf-notice-v1.accepted");
    private static ClientUpdateUi active;
    private ClientUpdateUi(View owner) { this.owner = owner; this.service = ClientUpdateService.INSTANCE; }
    public static ClientUpdateUi attach(View owner) {
        var ui = new ClientUpdateUi(owner); active = ui;
        if (ui.service != null) ui.subscription = ui.service.subscribe(state -> owner.post(() -> ui.render(state)));
        owner.post(ui::start);
        return ui;
    }
    private void start() {
        if (closed) return;
        if (BuildDistribution.EDITION.equals("cf") && !Files.isRegularFile(notice)) {
            modal = new Modal(owner.getContext(), text(tr("cfTitle")), content(tr("cfNotice")),
                    new Modal.ActionButton(tr("fullEdition"), (b, m) -> openFullEdition()),
                    new Modal.ActionButton(tr("understood"), (b, m) -> {
                        try { Files.createDirectories(notice.getParent()); Files.writeString(notice, "1\n", StandardOpenOption.CREATE_NEW); }
                        catch (java.io.IOException e) { MusicHud.LOGGER.warn("Could not save CF notice acknowledgement"); }
                        m.dismiss();
                    }));
            modal.setOnDismissListener(() -> { modal = null; if (!closed && service != null) render(service.state()); });
            modal.show();
        } else if (service != null) render(service.state());
    }
    public static void manualCheck() {
        if (active == null || active.closed) return;
        active.manual = true;
        if (active.service == null) { active.showMessage(tr("unavailable")); return; }
        if (active.service.state().status() == ClientUpdateService.Status.AVAILABLE || active.service.state().status() == ClientUpdateService.Status.READY) active.render(active.service.state());
        else active.service.check();
    }
    private void render(ClientUpdateService.State state) {
        if (closed || service == null || service.state() != state) return;
        if (progressText != null && modal != null && modal.isShowing()) {
            switch (state.status()) {
                case READY -> { progressText.setText(tr("ready")); progressText = null; manual = false; }
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
                if (!manual && version.equals(dismissedVersion)) return;
                manual = false; dismissedVersion = version;
                boolean cf = BuildDistribution.EDITION.equals("cf");
                String message = tr("available", version) + "\n\n" + tr(cf ? "manualDownloadNotice" : "restartNotice");
                if (BuildDistribution.EDITION.equals("cf")) message += "\n\n" + tr("cfNotice");
                var now = new Modal.ActionButton(tr(cf ? "goDownload" : "now"), (b, m) -> {
                    if (cf) net.minecraft.util.Util.getPlatform().openUri(state.offer().releasePageUri());
                    else service.download();
                    m.dismiss();
                });
                var later = new Modal.ActionButton(tr("later"), (b, m) -> m.dismiss());
                modal = BuildDistribution.EDITION.equals("cf")
                        ? new Modal(owner.getContext(), text(tr("cfTitle")), content(message), now, later, new Modal.ActionButton(tr("fullEdition"), (b, m) -> openFullEdition()))
                        : new Modal(owner.getContext(), text(tr("title")), content(message), now, later);
                modal.setOnDismissListener(() -> { modal = null; if (!closed && service.state().status() != ClientUpdateService.Status.AVAILABLE) render(service.state()); });
                modal.show();
            }
            case CHECKING -> { if (manual) showProgress(tr("checking")); }
            case DOWNLOADING -> showProgress(tr("downloading"));
            case READY -> showMessage(tr("ready"));
            case FAILED -> { if (manual || state.message().equals("download")) { manual = false; showMessage(tr("failed")); } }
            case CURRENT -> { if (manual) { manual = false; showMessage(tr("current")); } }
            default -> { }
        }
    }
    private void showProgress(String message) {
        progressText = text(message);
        modal = new Modal(owner.getContext(), progressText, new Modal.ActionButton(tr("understood"), (b, m) -> m.dismiss()));
        modal.setOnDismissListener(() -> {
            modal = null; progressText = null;
            if (!closed && service.state().status() == ClientUpdateService.Status.AVAILABLE) render(service.state());
        });
        modal.show();
    }
    private void showMessage(String message) {
        if (closed) return;
        modal = new Modal(owner.getContext(), text(message), new Modal.ActionButton(tr("understood"), (b, m) -> m.dismiss()));
        modal.setOnDismissListener(() -> modal = null); modal.show();
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
    private static void openFullEdition() { net.minecraft.util.Util.getPlatform().openUri(FULL_EDITION); }
    @Override public void close() {
        closed = true;
        if (active == this) active = null;
        if (subscription != null) try { subscription.close(); } catch (Exception ignored) { }
        if (modal != null) { modal.setOnDismissListener(() -> {}); modal.dismiss(); modal = null; }
    }
}
