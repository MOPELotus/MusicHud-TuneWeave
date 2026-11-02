package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.companion.CompanionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dynamic FPS can skip Minecraft's entire renderFrame, including desktop capture. */
@Pseudo
@Mixin(targets = "dynamic_fps.impl.DynamicFPSMod", remap = false)
public abstract class DynamicFpsCompanionMixin {
    @Inject(method = "targetFrameRate()I", at = @At("RETURN"), cancellable = true, remap = false)
    private static void musichud_tuneweave$desktopCadence(CallbackInfoReturnable<Integer> ci) {
        if (CompanionBridge.requiresFrameUpdates()) {
            ci.setReturnValue(Math.max(ci.getReturnValue(), CompanionBridge.fps()));
        }
    }
}
