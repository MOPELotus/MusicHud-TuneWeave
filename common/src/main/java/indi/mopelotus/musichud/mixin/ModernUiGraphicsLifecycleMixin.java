package indi.mopelotus.musichud.mixin;

import icyllis.modernui.mc.UIManager;
import icyllis.modernui.mc.MuiScreen;
import net.minecraft.client.gui.screens.Screen;
import icyllis.modernui.core.Core;
import icyllis.modernui.core.Choreographer;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.utils.ShutdownWaiter;
import indi.mopelotus.musichud.client.utils.UiShutdownRequest;
import indi.mopelotus.musichud.client.utils.UiQueueShutdown;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UIManager.class, remap = false)
public abstract class ModernUiGraphicsLifecycleMixin {
    @Shadow private static UIManager sInstance;
    @Shadow @Final private Thread mUiThread;
    @Shadow private volatile boolean mRunning;
    @Shadow private volatile boolean mShutdownRequested;
    @Shadow protected MuiScreen mScreen;
    @Shadow private void finish() { throw new AssertionError(); }
    @Unique private final UiShutdownRequest musichud_tuneweave$shutdownRequest = new UiShutdownRequest();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void musichud_tuneweave$registerShutdown(CallbackInfo ci) {
        ClientGraphicsResources.registerUiShutdown(this::musichud_tuneweave$awaitUiShutdown);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private static void musichud_tuneweave$beforeDestroy(CallbackInfo ci) {
        if (sInstance != null) {
            ((ModernUiGraphicsLifecycleMixin) (Object) sInstance).musichud_tuneweave$awaitUiShutdown();
        }
    }

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$skipStoppedDetach(Screen target, CallbackInfo ci) {
        // The stopped UI already detached the fragment. Retire only this screen's
        // main-thread identity so the following screen-change event cannot reuse it.
        if (!mRunning) {
            if (mScreen == target) mScreen = null;
            ci.cancel();
        }
    }

    @Inject(method = "onHoverMove", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$skipStoppedHover(CallbackInfo ci) {
        // Screen changes and synthetic cursor callbacks can outlive the UI queue.
        // This flag distinguishes shutdown from startup, before mRunning becomes true.
        if (mShutdownRequested) ci.cancel();
    }

    @ModifyArg(method = "finish", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/core/Handler;postDelayed(Ljava/lang/Runnable;J)Z"), index = 0)
    private Runnable musichud_tuneweave$stopAnimationsBeforeQueueQuit(Runnable quit) {
        // quitSafely still dispatches already-due frame messages. Their animation callbacks
        // must be removed first, otherwise AnimationHandler tries to schedule another frame
        // on a queue that has already stopped accepting messages.
        return UiQueueShutdown.wrap(() -> Choreographer.getInstance().removeCallbacks(
                Choreographer.CALLBACK_ANIMATION, null, null), quit);
    }

    private void musichud_tuneweave$awaitUiShutdown() {
        if (!mUiThread.isAlive()) return;
        UIManager manager = (UIManager) (Object) this;
        // Older ModernUI versions release their immediate context before joining the UI thread,
        // and a last frame can leave that thread asleep forever. Finish it while the device is live.
        musichud_tuneweave$shutdownRequest.request(manager, () -> {
            if (mRunning) {
                mRunning = false;
                Core.getUiHandlerAsync().post(this::finish);
            }
        });
        var decor = manager.getDecorView();
        var root = decor == null ? null : decor.getViewRoot();
        Object frameLock = null;
        if (root != null) {
            try {
                // ModernUI loads ViewRoot before mod mixins are applied, so an accessor mixin cannot target it.
                var field = icyllis.modernui.view.ViewRoot.class.getDeclaredField("mRenderLock");
                field.setAccessible(true);
                frameLock = field.get(root);
            } catch (ReflectiveOperationException error) {
                MusicHud.LOGGER.warn("Could not wake the final UI frame", error);
            }
        }
        if (!ShutdownWaiter.await(mUiThread, frameLock, 1000)) {
            MusicHud.LOGGER.warn("UI thread did not finish before graphics shutdown");
        }
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/core/Core;requireUiRecordingContext()Licyllis/arc3d/granite/RecordingContext;"))
    private void musichud_tuneweave$closeImages(CallbackInfo ci) {
        ClientGraphicsResources.finishUiShutdown();
    }
}
