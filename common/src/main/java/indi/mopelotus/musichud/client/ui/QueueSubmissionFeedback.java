package indi.mopelotus.musichud.client.ui;

import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.view.View;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.MusicDetail;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.services.music.QueueSubmissionGate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Queue feedback belongs to the clicked view lifetime, account and screen. */
public final class QueueSubmissionFeedback {
    private QueueSubmissionFeedback() {}

    public static void submit(View view, MusicDetail music) {
        if (!view.isAttachedToWindow()) return;
        Object account = MusicEntityCache.captureGeneration();
        Object screen = Minecraft.getInstance().screen;
        var origin = Minecraft.getInstance().getConnection();
        var transport = origin == null ? null : origin.getConnection();
        var attached = new AtomicBoolean(true);
        var listener = new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View ignored) {}
            public void onViewDetachedFromWindow(View ignored) { attached.set(false); }
        };
        view.addOnAttachStateChangeListener(listener);
        var request = MusicService.getInstance().submitMusicToQueue(music,
                () -> account == MusicEntityCache.captureGeneration());
        observe(request, MuiModApi::postToUiThread,
                () -> attached.get() && view.isAttachedToWindow()
                        && account == MusicEntityCache.captureGeneration()
                        && screen == Minecraft.getInstance().screen
                        && (transport == null || transport.isConnected()),
                result -> {
                    String key = result == QueueSubmissionGate.Result.SUBMITTED
                            ? ".text.queueRequestSubmitted" : ".text.queueConnectionNotReady";
                    ToastUtil.showOnUiThread(Toast.makeText(view.getContext(), I18n.get(MusicHud.MOD_ID + key), Toast.LENGTH_SHORT));
                }, () -> view.removeOnAttachStateChangeListener(listener));
    }

    static void observe(QueueSubmissionGate.Submission request, Executor ui, BooleanSupplier visible,
                        Consumer<QueueSubmissionGate.Result> feedback, Runnable settled) {
        request.result().whenComplete((result, error) -> ui.execute(() -> {
            try {
                if (!visible.getAsBoolean()) return;
                var outcome = error == null ? result : QueueSubmissionGate.Result.REJECTED;
                // A rejected transport must still explain failure on the current page.
                if (outcome == QueueSubmissionGate.Result.SUBMITTED && !request.current().getAsBoolean()) return;
                feedback.accept(outcome);
            } finally { settled.run(); }
        }));
    }
}
