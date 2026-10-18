package indi.mopelotus.musichud.client.ui.hud.renderer;

import indi.mopelotus.musichud.client.ui.hud.metadata.Layout;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class PlayerHeadRenderer implements HudRenderer {
    private static final int SKIN_TEXTURE_SIZE = 64;
    @Getter
    private Layout layout;
    @Getter
    private volatile Supplier<Identifier> playerSkinSupplier;
    // Transition state belongs exclusively to the render thread.
    private final SkinTransition<Identifier> transition = new SkinTransition<>(400_000_000L);

    public void setPlayerSkinSupplier(@Nullable Supplier<Identifier> playerSkinSupplier) {
        this.playerSkinSupplier = playerSkinSupplier;
    }

    public boolean isVisible() { return transition.isVisible(); }

    public void configure(Layout layout) {
        this.layout = layout;
    }

    @Override
    public void render(HudRenderContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) return;
        Identifier skin = null;
        Supplier<Identifier> supplier = playerSkinSupplier;
        if (supplier != null) {
            try {
                skin = supplier.get();
            } catch (Exception ignored) {
                // A stale player lookup must not abort extraction of the HUD frame.
            }
        }
        var layers = transition.sample(skin, System.nanoTime());
        if (layers.isEmpty()) return;

        Layout.AbsolutePosition absolutePosition = layout.calcAbsolutePosition(context);
        int w = (int) layout.getWidth();
        int h = (int) layout.getHeight();
        float styleAlpha = layout.getEffectiveAlpha();
        for (var layer : layers) {
            renderHead(context.getGraphics(), layer.skin(),
                    absolutePosition.x(), absolutePosition.y(), w, h, layer.alpha() * styleAlpha);
        }
    }

    public static void renderHead(GuiGraphicsExtractor gr, Identifier skin,
                                   float x, float y, int w, int h, float alpha) {
        if (alpha <= 0.003) {
            return;
        }
        float scale = 0.87f;
        float inset = (1 - scale) / 2;
        gr.nextStratum();

        int alphaColor = ARGB.color(Math.min(alpha, 1), 0xFFFFFF);
        // Each layer restores the caller's pose even if a texture has just been invalidated.
        gr.pose().pushMatrix();
        try {
            gr.pose().translate(x + w * inset, y + h * inset);
            gr.pose().scale(scale);
            gr.blit(RenderPipelines.GUI_TEXTURED, skin,
                    0, 0, 8, 8, w, h, 8, 8, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE, alphaColor);
        } finally {
            gr.pose().popMatrix();
        }

        gr.pose().pushMatrix();
        try {
            gr.pose().translate(x, y);
            gr.blit(RenderPipelines.GUI_TEXTURED, skin,
                    0, 0, 40, 8, w, h, 8, 8, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE, alphaColor);
        } finally {
            gr.pose().popMatrix();
        }
    }
}
