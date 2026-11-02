package indi.mopelotus.musichud.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import indi.mopelotus.musichud.client.companion.CompanionBridge;
import indi.mopelotus.musichud.client.companion.CompanionDiagnostics;
import com.mojang.blaze3d.platform.SDLEventHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftCompanionFrameMixin {
    @WrapOperation(method = "run", at = @At(value = "INVOKE", target =
            "Lcom/mojang/blaze3d/systems/RenderSystem;pollEvents(Lcom/mojang/blaze3d/platform/SDLEventHandler;)V"))
    private void musichud_tuneweave$measureEvents(SDLEventHandler handler, Operation<Void> original) {
        CompanionDiagnostics.measure("SDL event polling", () -> original.call(handler));
    }

    @WrapOperation(method = "run", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/Minecraft;runTick(Z)V"))
    private void musichud_tuneweave$measureTick(Minecraft minecraft, boolean tick, Operation<Void> original) {
        CompanionDiagnostics.measure("game tick and frame", () -> original.call(minecraft, tick));
    }

    @Inject(method = "runTick", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$restoreDesktopPreferences(boolean tick, CallbackInfo ci) {
        // Dynamic FPS may skip renderFrame entirely while minimized.
        if (!CompanionBridge.prepareTick()) {
            var minecraft = Minecraft.getInstance();
            if (minecraft.getWindow().shouldClose()) minecraft.stop();
            FramerateLimiter.limitDisplayFPS(15);
            ci.cancel();
        }
    }

    @Inject(method = "renderFrame", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$prepareDesktopDrawable(boolean tick, CallbackInfo ci) {
        // Also precedes executePendingTasks: readback callbacks need a usable drawable.
        if (!CompanionBridge.prepareFrame()) {
            FramerateLimiter.limitDisplayFPS(15);
            ci.cancel();
        }
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target =
            "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V", remap = false))
    private void musichud_tuneweave$skipMinimizedSurface(GpuSurface surface, Operation<Void> original) {
        // Keep vanilla callback polling, command submission, uniform reset and pacing.
        if (!CompanionBridge.renderingWithoutGameSurface()) original.call(surface);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/GameRenderer;extract(Lnet/minecraft/client/DeltaTracker;Z)V"))
    private void musichud_tuneweave$skipMinimizedGameExtraction(GameRenderer renderer, DeltaTracker delta,
                                                              boolean tick, Operation<Void> original) {
        // ModernUI submits GPU work while extracting a screen. A hidden game frame
        // must skip extraction as well as rendering; desktop extraction is independent.
        if (!CompanionBridge.renderingWithoutGameSurface()) original.call(renderer, delta, tick);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/client/renderer/GameRenderer;render()V"))
    private void musichud_tuneweave$renderMinimizedDesktop(GameRenderer renderer, Operation<Void> original) {
        if (CompanionBridge.renderingWithoutGameSurface()) CompanionBridge.render();
        else original.call(renderer);
    }

}
