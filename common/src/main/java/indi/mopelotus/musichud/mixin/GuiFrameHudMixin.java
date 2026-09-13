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
    @ModifyExpressionValue(method = "render", at = @At(value = "NEW", target = "net/minecraft/client/gui/GuiGraphics"))
    private GuiGraphics musichud_tuneweave$captureFrame(GuiGraphics graphics,
            @Share("musichud_tuneweave$frameGraphics") LocalRef<GuiGraphics> frame) {
        frame.set(graphics);
        return graphics;
    }

    // Full-frame boundary after screens, overlays and toasts, before the GUI model-view stack is restored.
    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lorg/joml/Matrix4fStack;popMatrix()Lorg/joml/Matrix4fStack;", remap = false))
    private void musichud_tuneweave$renderFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci,
            @Share("musichud_tuneweave$frameGraphics") LocalRef<GuiGraphics> frame) {
        if (!Minecraft.getInstance().isGameLoadFinished()) return;
        if (frame.get() == null || indi.mopelotus.musichud.client.ui.hud.pipelines.HudShaderManager.isClosed()) return;
        frame.get().flush();
        HudRendererManager.getInstance().renderFrame(frame.get(), deltaTracker);
    }
}
