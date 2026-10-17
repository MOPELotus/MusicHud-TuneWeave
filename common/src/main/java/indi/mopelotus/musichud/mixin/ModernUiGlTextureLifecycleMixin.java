package indi.mopelotus.musichud.mixin;

import icyllis.arc3d.opengl.GLDevice;
import icyllis.arc3d.opengl.GLTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A label update queued on the UI thread may execute after the texture has been released. */
@Mixin(targets = "icyllis.arc3d.opengl.GLTexture", remap = false)
public abstract class ModernUiGlTextureLifecycleMixin {
    @Inject(method = "lambda$onSetLabel$1", at = @At("HEAD"), cancellable = true)
    private void musichud_tuneweave$rejectReleasedLabel(String label, GLDevice device, CallbackInfo ci) {
        GLTexture texture = (GLTexture) (Object) this;
        if (texture.isDestroyed() || texture.getHandle() == 0) ci.cancel();
    }
}
