package indi.mopelotus.musichud.mixin;

import indi.mopelotus.musichud.client.ui.hud.pipelines.HudShaderManager;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class HudShaderLifecycleMixin {
    @Inject(method = "reloadShaders", at = @At("HEAD"))
    private void musichud_tuneweave$reload(ResourceProvider resources, CallbackInfo ci) {
        HudShaderManager.invalidate();
    }
    @Inject(method = "close", at = @At("HEAD"))
    private void musichud_tuneweave$close(CallbackInfo ci) {
        HudShaderManager.shutdown();
    }
}
