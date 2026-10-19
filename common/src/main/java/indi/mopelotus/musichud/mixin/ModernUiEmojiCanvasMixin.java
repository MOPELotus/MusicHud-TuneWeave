package indi.mopelotus.musichud.mixin;

import icyllis.modernui.graphics.pipeline.ArcCanvas;
import indi.mopelotus.musichud.client.utils.text.EmojiAwareCanvas;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** One shared widget canvas, including ordinary TextViews and custom shaped lyric spans. */
@Mixin(targets = "icyllis.modernui.mc.UIManager$ViewRootImpl", remap = false)
public abstract class ModernUiEmojiCanvasMixin {
    @Redirect(method = "beginDrawLocked", at = @At(value = "NEW", target =
            "(Licyllis/arc3d/sketch/Canvas;)Licyllis/modernui/graphics/pipeline/ArcCanvas;"))
    private ArcCanvas musichud_tuneweave$emojiCanvas(icyllis.arc3d.sketch.Canvas canvas) {
        return EmojiAwareCanvas.create(canvas);
    }
}
