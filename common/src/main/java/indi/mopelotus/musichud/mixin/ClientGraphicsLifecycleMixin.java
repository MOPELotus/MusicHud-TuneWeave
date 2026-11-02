package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import indi.mopelotus.musichud.client.companion.CompanionDiagnostics;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientGraphicsLifecycleMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void musichud_tuneweave$closeGraphics(CallbackInfo ci) {
        CompanionDiagnostics.beginShutdown();
        try {
            CompanionDiagnostics.measure("TuneWeave graphics shutdown", ClientGraphicsResources::beginShutdown);
        } catch (RuntimeException | Error failure) {
            // Minecraft may terminate in a finally block before reporting a close failure.
            indi.mopelotus.musichud.MusicHud.LOGGER.error("Failed to shut down TuneWeave graphics", failure);
            throw failure;
        }
    }

    @Inject(method = "close", at = @At("RETURN"))
    private void musichud_tuneweave$finishCloseDiagnostics(CallbackInfo ci) {
        CompanionDiagnostics.finishShutdown();
    }

}
