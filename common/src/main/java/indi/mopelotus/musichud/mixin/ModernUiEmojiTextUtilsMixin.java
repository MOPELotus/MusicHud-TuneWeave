package indi.mopelotus.musichud.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import icyllis.arc3d.sketch.TextBlob;
import icyllis.modernui.graphics.Canvas;
import icyllis.modernui.graphics.Paint;
import icyllis.modernui.graphics.text.FontMetricsInt;
import icyllis.modernui.graphics.text.FontPaint;
import icyllis.modernui.graphics.text.ShapedText;
import indi.mopelotus.musichud.client.utils.text.EmojiAwareCanvas;
import java.util.ArrayList;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** ModernUI 3.12 plain-layout fast paths build blobs in these static helpers. */
@Mixin(targets = "icyllis.modernui.text.TextUtils", remap = false)
public abstract class ModernUiEmojiTextUtilsMixin {
    @WrapOperation(method = "drawTextRun", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/graphics/text/ShapedText;doLayoutRun([CIIIIZLicyllis/modernui/graphics/text/FontPaint;Licyllis/modernui/graphics/text/FontMetricsInt;Licyllis/modernui/graphics/text/ShapedText$RunConsumer;)F"),
            require = 2, allow = 2)
    private static float musichud_tuneweave$captureEmoji(char[] text, int contextStart, int contextEnd,
            int start, int end, boolean rtl, FontPaint paint, FontMetricsInt metrics,
            ShapedText.RunConsumer consumer, Operation<Float> original,
            @Share("musichud_tuneweave$emojiRuns") LocalRef<List<EmojiAwareCanvas.EmojiRun>> runs) {
        if (consumer == null) {
            return original.call(text, contextStart, contextEnd, start, end, rtl, paint, metrics, null);
        }
        ShapedText.RunConsumer collecting = EmojiAwareCanvas.collectEmojiRuns(consumer, run -> {
            List<EmojiAwareCanvas.EmojiRun> local = runs.get();
            if (local == null) {
                local = new ArrayList<>();
                runs.set(local);
            }
            local.add(run);
        });
        return original.call(text, contextStart, contextEnd, start, end, rtl, paint, metrics, collecting);
    }

    @WrapOperation(method = "drawTextRun", at = @At(value = "INVOKE", target =
            "Licyllis/modernui/graphics/Canvas;drawTextBlob(Licyllis/arc3d/sketch/TextBlob;FFLicyllis/modernui/graphics/Paint;)V"),
            require = 2, allow = 2)
    private static void musichud_tuneweave$drawEmoji(Canvas canvas, TextBlob blob, float x, float baseline,
            Paint paint, Operation<Void> original,
            @Share("musichud_tuneweave$emojiRuns") LocalRef<List<EmojiAwareCanvas.EmojiRun>> runs) {
        original.call(canvas, blob, x, baseline, paint);
        if (canvas instanceof EmojiAwareCanvas images) {
            // A null blob is normal for an all-emoji line; the image pass is still required.
            images.drawEmojiRuns(runs.get(), x, baseline, paint);
        }
    }
}
