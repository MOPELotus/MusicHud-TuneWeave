package indi.mopelotus.musichud.mixin;

import icyllis.modernui.mc.UIManager;
import icyllis.modernui.core.Core;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.utils.ShutdownWaiter;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UIManager.class, remap = false)
public abstract class ModernUiGraphicsLifecycleMixin {
    @Shadow private static UIManager sInstance;
    @Shadow @Final private Thread mUiThread;
    @Shadow private volatile boolean mRunning;
    @Shadow private void finish() { throw new AssertionError(); }

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

    private void musichud_tuneweave$awaitUiShutdown() {
        if (!mUiThread.isAlive()) return;
        UIManager manager = (UIManager) (Object) this;
        // Older ModernUI versions release their immediate context before joining the UI thread,
        // and a last frame can leave that thread asleep forever. Finish it while the device is live.
        if (mRunning) {
            mRunning = false;
            Core.getUiHandlerAsync().post(this::finish);
        }
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
