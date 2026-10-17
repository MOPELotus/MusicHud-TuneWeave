package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.utils.image.ClientGraphicsResources;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ClientGraphicsLifecycleMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void musichud_tuneweave$closeGraphics(CallbackInfo ci) {
        try {
            ClientGraphicsResources.beginShutdown();
        } catch (RuntimeException | Error failure) {
            // Minecraft may terminate in a finally block before reporting a close failure.
            indi.mopelotus.musichud.MusicHud.LOGGER.error("Failed to shut down TuneWeave graphics", failure);
            throw failure;
        }
    }
}
