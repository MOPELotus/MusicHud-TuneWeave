package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import icyllis.modernui.mc.MuiModApi;
import icyllis.modernui.widget.Toast;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.services.LoginService;
import indi.mopelotus.musichud.client.services.music.MusicEntityCache;
import indi.mopelotus.musichud.client.ui.AsyncToggleController;
import indi.mopelotus.musichud.client.ui.ToastUtil;
import indi.mopelotus.musichud.interfaces.Unregister;
import net.minecraft.client.resources.language.I18n;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Shared UI lifecycle for account-bound subscription and favorite toggles. */
abstract class AsyncStateToggleButton extends ToggleIconButton {
    private final AsyncToggleController controller = new AsyncToggleController(MuiModApi::postToUiThread);
    private Supplier<CompletableFuture<Boolean>> reader;
    private Function<Boolean, CompletableFuture<?>> writer;
    private Function<Consumer<Boolean>, Unregister> observer;
    private Unregister subscription, loginSubscription;
    private Object scope;

    AsyncStateToggleButton(Context context, Appearance appearance) { super(context, appearance); }

    protected void bindAsync(Supplier<CompletableFuture<Boolean>> reader, Function<Boolean, CompletableFuture<?>> writer,
                             Function<Consumer<Boolean>, Unregister> observer) {
        this.reader = reader; this.writer = writer; this.observer = observer;
        refreshBinding();
    }

    private void refreshBinding() {
        if (subscription != null) { subscription.unregister(); subscription = null; }
        controller.unbind();
        scope = MusicEntityCache.captureGeneration();
        if (reader == null || !LoginService.getInstance().isLogined()) {
            setChecked(false); setEnabled(false);
            setTooltipText(I18n.get(MusicHud.MOD_ID + ".text.loginRequired"));
            return;
        }
        Object expected = scope;
        var read = reader; var observe = observer;
        var write = indi.mopelotus.musichud.client.services.tuneweave.TuneWeaveClientService.getInstance().prepareFunction(writer);
        controller.bind(() -> CompletableFuture.supplyAsync(read, MusicHud.EXECUTOR).thenCompose(Function.identity()), selected -> {
                    var result = new java.util.concurrent.atomic.AtomicReference<CompletableFuture<?>>();
                    MusicEntityCache.publish(expected, () -> result.set(write.apply(selected)));
                    return result.get();
                },
                (selected, enabled) -> {
                    if (MusicEntityCache.captureGeneration() != expected) { refreshBinding(); return; }
                    setChecked(selected); setEnabled(enabled);
                    setTooltipText(selected ? getTooltipTextOn() : getTooltipTextOff());
                    if (controller.initialized() && subscription == null && isAttachedToWindow()) {
                        long binding = controller.binding();
                        subscription = observe.apply(value -> controller.external(binding, value));
                    }
                }, error -> {
                    if (MusicEntityCache.captureGeneration() != expected || !isAttachedToWindow()) return;
                    setTooltipText(I18n.get(MusicHud.MOD_ID + ".button.retry"));
                    // A passive state lookup is not a user mutation. Keep its retry
                    // affordance on the button instead of flooding the screen with toasts.
                    if (controller.initialized())
                        ToastUtil.show(Toast.makeText(getContext(), I18n.get(MusicHud.MOD_ID + ".text.operationFailed"), Toast.LENGTH_SHORT));
                });
    }

    @Override public boolean performClick() {
        if (MusicEntityCache.captureGeneration() != scope) { refreshBinding(); return false; }
        if (!isEnabled()) return false;
        boolean handled = super.performClick();
        controller.request(isChecked());
        return handled;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (loginSubscription == null) loginSubscription = LoginService.getInstance().addLoginStateListener(state ->
                MuiModApi.postToUiThread(() -> { if (isAttachedToWindow()) refreshBinding(); }));
        refreshBinding();
    }

    @Override protected void onDetachedFromWindow() {
        controller.unbind();
        if (subscription != null) { subscription.unregister(); subscription = null; }
        if (loginSubscription != null) { loginSubscription.unregister(); loginSubscription = null; }
        super.onDetachedFromWindow();
    }
}
