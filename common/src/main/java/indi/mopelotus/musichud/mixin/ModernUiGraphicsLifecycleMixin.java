package indi.mopelotus.musichud.mixin;

import icyllis.modernui.mc.UIManager;
import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UIManager.class, remap = false)
public abstract class ModernUiGraphicsLifecycleMixin {
    @Inject(method = "run", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/core/Core;requireUiRecordingContext()Licyllis/arc3d/granite/RecordingContext;"))
    private void musichud_tuneweave$closeImages(CallbackInfo ci) {
        ClientGraphicsResources.finishUiShutdown();
    }
}
