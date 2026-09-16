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
        ClientGraphicsResources.beginShutdown();
    }
}
