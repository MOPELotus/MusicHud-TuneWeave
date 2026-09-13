package indi.mopelotus.musichud.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
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
    // ModernUI can remove the original local-variable metadata at the draw boundary.
    // Share the constructed graphics only within this render invocation, including menus.
    @ModifyExpressionValue(method = "render", at = @At(value = "NEW", target = "net/minecraft/client/gui/GuiGraphics"))
    private GuiGraphics musichud_tuneweave$captureFrame(GuiGraphics graphics,
            @Share("musichud_tuneweave$frameGraphics") LocalRef<GuiGraphics> frame) {
        frame.set(graphics);
        return graphics;
    }

    // Minecraft 1.21.6–1.21.8 collects screens, overlays and world HUD in GameRenderer.
    // Submit once after all GUI content and before its single GPU render pass.
    @Inject(method = "render", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V"))
    private void musichud_tuneweave$renderFrame(DeltaTracker deltaTracker, boolean renderLevel,
            CallbackInfo ci, @Share("musichud_tuneweave$frameGraphics") LocalRef<GuiGraphics> frame) {
        // Match 26.2's resourcesLoaded gate: ModernUI shaders are unavailable during initial loading.
        if (!Minecraft.getInstance().isGameLoadFinished()) return;
        GuiGraphics graphics = frame.get();
        if (graphics == null) return;
        graphics.nextStratum();
        HudRendererManager.getInstance().renderFrame(graphics, deltaTracker);
    }
}
