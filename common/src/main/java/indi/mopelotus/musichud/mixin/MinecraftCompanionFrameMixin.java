package indi.mopelotus.musichud.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import indi.mopelotus.musichud.client.companion.CompanionBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Minecraft.class)
public abstract class MinecraftCompanionFrameMixin {
 @Inject(method="runTick", at=@At("HEAD"))
 private void prepare(boolean tick, CallbackInfo ci) { CompanionBridge.prepareTick(); CompanionBridge.prepareFrame(); }
 @WrapOperation(method="runTick",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker;Z)V"))
 private void desktop(GameRenderer renderer, DeltaTracker delta, boolean tick, Operation<Void> original) {
  if (CompanionBridge.renderingWithoutGameSurface()) CompanionBridge.render(); else original.call(renderer,delta,tick);
 }
}
