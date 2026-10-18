package indi.mopelotus.musichud.client.ui.hud.renderer;

import indi.mopelotus.musichud.client.ui.hud.metadata.Layout;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

public class PlayerHeadRenderer implements HudRenderer {
    private static final int SKIN_TEXTURE_SIZE = 64;
    @Getter
    private Layout layout;
    @Getter
    private volatile Supplier<ResourceLocation> playerSkinSupplier;
    // Transition state belongs exclusively to the render thread.
    private final SkinTransition<ResourceLocation> transition = new SkinTransition<>(400_000_000L);

    public void setPlayerSkinSupplier(@Nullable Supplier<ResourceLocation> playerSkinSupplier) {
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
        ResourceLocation skin = null;
        Supplier<ResourceLocation> supplier = playerSkinSupplier;
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

    public static void renderHead(GuiGraphics gr, ResourceLocation skin,
                                   float x, float y, int w, int h, float alpha) {
        if (alpha <= 0.003) {
            return;
        }
        float scale = 0.87f;
        float inset = (1 - scale) / 2;
        float[] previousColor = RenderSystem.getShaderColor().clone();
        PoseStack pose = gr.pose();
        try {
            gr.setColor(1, 1, 1, Math.min(alpha, 1));
            pose.pushPose();
            try {
                pose.translate(x + w * inset, y + h * inset, 0);
                pose.scale(scale, scale, 1);
                gr.blit(skin, 0, 0, w, h, 8, 8, 8, 8, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
            } finally { pose.popPose(); }
            pose.pushPose();
            try {
                pose.translate(x, y, 0);
                gr.blit(skin, 0, 0, w, h, 40, 8, 8, 8, SKIN_TEXTURE_SIZE, SKIN_TEXTURE_SIZE);
            } finally { pose.popPose(); }
        } finally {
            gr.setColor(previousColor[0], previousColor[1], previousColor[2], previousColor[3]);
        }
    }
}
