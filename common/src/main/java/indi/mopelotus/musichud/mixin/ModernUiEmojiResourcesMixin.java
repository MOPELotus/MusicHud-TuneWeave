package indi.mopelotus.musichud.mixin;

import icyllis.modernui.graphics.text.EmojiFont;
import icyllis.modernui.mc.FontResourceManager;
import indi.mopelotus.musichud.client.utils.text.WidgetEmojiResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FontResourceManager.class, remap = false)
public abstract class ModernUiEmojiResourcesMixin {
    @Shadow protected EmojiFont mEmojiFont;

    @Inject(method = "applyResources", at = @At("TAIL"))
    private void musichud_tuneweave$publishEmojiResources(CallbackInfo ci) {
        WidgetEmojiResources.onResourceReload(mEmojiFont);
    }
}
