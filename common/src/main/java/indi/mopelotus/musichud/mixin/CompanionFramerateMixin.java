package indi.mopelotus.musichud.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import indi.mopelotus.musichud.client.companion.CompanionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class CompanionFramerateMixin {
    @Inject(method = "getFramerateLimit", at = @At("RETURN"), cancellable = true)
    private void musichud_tuneweave$desktopCadence(CallbackInfoReturnable<Integer> ci) {
        if (CompanionBridge.requiresFrameUpdates()) ci.setReturnValue(Math.max(ci.getReturnValue(), CompanionBridge.fps()));
    }

}
