package indi.mopelotus.musichud.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import indi.mopelotus.musichud.client.ui.hud.renderer.HudRenderContext;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public class GuiRendererHudMixin {
    @ModifyVariable(method = "executeDrawRange", at = @At("HEAD"), argsOnly = true)
    private RenderTarget musichud_tuneweave$desktopTarget(RenderTarget original) {
        return indi.mopelotus.musichud.client.companion.CompanionBridge.destination((GuiRenderer) (Object) this, original);
    }

    @Inject(method = "executeDrawRange", at = @At("RETURN"))
    private void musichud_tuneweave$replayCompanion(CallbackInfo ci) {
        indi.mopelotus.musichud.client.companion.CompanionBridge.replay((GuiRenderer) (Object) this, null, null, null, 0, 0);
    }

    @Inject(method = "executeDrawRange",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;bindDefaultUniforms" +
                    "(Lcom/mojang/blaze3d/systems/RenderPass;)V", shift = At.Shift.AFTER, remap = false),
            locals = LocalCapture.CAPTURE_FAILSOFT)
    private void onExecuteDrawRange(Supplier<String> label, RenderTarget mainRenderTarget,
                                    GpuBufferSlice fog, GpuBufferSlice dynamicTransforms,
                                    GpuBuffer indexBuffer, VertexFormat.IndexType indexType,
                                    int startIndex, int endIndex,
                                    CallbackInfo ci, RenderPass renderPass) {
        HudRenderContext ctx = HudRenderContext.getCurrent();
        if (ctx != null) {
            ctx.bindAllUniforms(renderPass);
        }
    }
}
