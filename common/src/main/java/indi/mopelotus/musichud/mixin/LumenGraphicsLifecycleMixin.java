package indi.mopelotus.musichud.mixin;

import icyllis.modernui.mc.UIManager;
import icyllis.modernui.mc.MuiScreen;
import icyllis.modernui.core.Choreographer;
import indi.mopelotus.musichud.client.utils.UiQueueShutdown;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lumen owns shutdown ordering; MusicHud only releases its resources and guards late UI events. */
@Mixin(value = UIManager.class, remap = false)
public abstract class LumenGraphicsLifecycleMixin {
    @Shadow private volatile boolean mRunning;
    @Shadow private volatile boolean mShutdownRequested;
    @Shadow protected MuiScreen mScreen;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void musichud_tuneweave$registerShutdown(CallbackInfo ci) {
        ClientGraphicsResources.registerUiShutdown(UIManager::shutdownUiAndAudio);
    }

    @Inject(method = "removed", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$skipStoppedDetach(Screen target, CallbackInfo ci) {
        if (!mRunning) {
            if (mScreen == target) mScreen = null;
            ci.cancel();
        }
    }

    @Inject(method = "onHoverMove", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$skipStoppedHover(CallbackInfo ci) {
        if (mShutdownRequested) ci.cancel();
    }

    @ModifyArg(method = "finish", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/core/Handler;postDelayed(Ljava/lang/Runnable;J)Z"), index = 0)
    private Runnable musichud_tuneweave$stopAnimationsBeforeQueueQuit(Runnable quit) {
        return UiQueueShutdown.wrap(() -> {
            var frames = Choreographer.getInstance();
            // A queued traversal may invalidate fading scrollbars while quitSafely drains.
            // Stop every frame phase before quitting, including due traversal callbacks.
            frames.removeCallbacks(Choreographer.CALLBACK_INPUT, null, null);
            frames.removeCallbacks(Choreographer.CALLBACK_ANIMATION, null, null);
            frames.removeCallbacks(Choreographer.CALLBACK_TRAVERSAL, null, null);
            frames.removeCallbacks(Choreographer.CALLBACK_COMMIT, null, null);
        }, quit);
    }

    @Inject(method = "run", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/core/Core;requireUiRecordingContext()Licyllis/arc3d/granite/RecordingContext;"))
    private void musichud_tuneweave$closeImages(CallbackInfo ci) {
        ClientGraphicsResources.finishUiShutdown();
    }
}
