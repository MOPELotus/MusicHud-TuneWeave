package indi.mopelotus.musichud.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(GameRenderer.class)
public abstract class GuiFrameHudMixin {
    // The full frame includes screens and overlays, even while no level is loaded.
    // Registering a loader HUD event as well would extract and advance our GPU buffers twice.
    @Inject(method = "extractGui", at = @At("RETURN"), locals = LocalCapture.CAPTURE_FAILHARD)
    private void musichud_tuneweave$renderFrame(DeltaTracker deltaTracker, boolean shouldRenderLevel,
                                               boolean resourcesLoaded, CallbackInfo ci,
                                               ProfilerFiller profiler, int mouseX, int mouseY,
                                               GuiGraphicsExtractor graphics) {
        if (!resourcesLoaded) return;
        graphics.nextStratum();
        indi.mopelotus.musichud.client.companion.CompanionBridge.extract(graphics, deltaTracker);
    }
}
