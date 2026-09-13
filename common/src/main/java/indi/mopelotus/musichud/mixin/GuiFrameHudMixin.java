package indi.mopelotus.musichud.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import indi.mopelotus.musichud.client.ui.hud.HudRendererManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GuiFrameHudMixin {
    // Minecraft 1.21.11 collects screens, overlays and world HUD in GameRenderer.
    // Submit once after all GUI content and before its single GPU render pass.
    @Inject(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void musichud_tuneweave$renderFrame(DeltaTracker deltaTracker, boolean renderLevel,
                                               CallbackInfo ci, @Local GuiGraphics graphics) {
        // Preserve the 26.2 resource gate before touching ModernUI shaders during initial loading.
        if (!Minecraft.getInstance().isGameLoadFinished()) return;
        graphics.nextStratum();
        HudRendererManager.getInstance().renderFrame(graphics, deltaTracker);
    }
}
