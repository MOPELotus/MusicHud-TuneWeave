package indi.mopelotus.musichud.mixin;
import com.mojang.blaze3d.platform.Window;
import indi.mopelotus.musichud.client.companion.CompanionBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** Render-thread scoped dimensions; real window and game GUI settings are unchanged. */
@Mixin(Window.class)
public abstract class WindowCompanionMixin {
 @Inject(method="getWidth",at=@At("HEAD"),cancellable=true) private void width(CallbackInfoReturnable<Integer> ci){var c=CompanionBridge.desktopCanvas();if(c!=null)ci.setReturnValue(c.width());}
 @Inject(method="getHeight",at=@At("HEAD"),cancellable=true) private void height(CallbackInfoReturnable<Integer> ci){var c=CompanionBridge.desktopCanvas();if(c!=null)ci.setReturnValue(c.height());}
 @Inject(method="getGuiScaledWidth",at=@At("HEAD"),cancellable=true) private void guiWidth(CallbackInfoReturnable<Integer> ci){var c=CompanionBridge.desktopCanvas();if(c!=null)ci.setReturnValue(c.guiWidth());}
 @Inject(method="getGuiScaledHeight",at=@At("HEAD"),cancellable=true) private void guiHeight(CallbackInfoReturnable<Integer> ci){var c=CompanionBridge.desktopCanvas();if(c!=null)ci.setReturnValue(c.guiHeight());}
 @Inject(method="getGuiScale",at=@At("HEAD"),cancellable=true) private void scale(CallbackInfoReturnable<Integer> ci){var c=CompanionBridge.desktopCanvas();if(c!=null)ci.setReturnValue(c.scale());}
}
