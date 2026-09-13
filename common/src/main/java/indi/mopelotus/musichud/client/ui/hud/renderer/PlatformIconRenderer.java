package indi.mopelotus.musichud.client.ui.hud.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.client.ui.hud.metadata.Layout;
import indi.mopelotus.musichud.server.api.tuneweave.TuneWeavePlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;

/** Draws the current TuneWeave source mark in the HUD title row. */
public final class PlatformIconRenderer implements HudRenderer {
    private static final Map<IconKey, Identifier> TEXTURES = new HashMap<>();
    private static final Set<IconKey> FAILED = new HashSet<>();
    private Layout layout;
    private volatile TuneWeavePlatform platform;

    public void configure(Layout layout) {
        this.layout = layout;
    }

    public void setPlatform(TuneWeavePlatform platform) {
        this.platform = platform;
    }

    @Override
    public void render(HudRenderContext context) {
        if (layout == null || platform == null) return;
        Layout.AbsolutePosition position = layout.calcAbsolutePosition(context);
        int x = (int) position.x();
        int y = (int) position.y();
        int width = Math.max(1, (int) layout.getWidth());
        int height = Math.max(1, (int) layout.getHeight());
        double guiScale = Minecraft.getInstance().getWindow().getGuiScale();
        int rasterSize = Math.clamp((int) Math.ceil(Math.max(width, height) * guiScale), 16, 128);
        Identifier texture = texture(platform, rasterSize);
        if (texture == null) return;
        context.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0, 0,
                width, height, width, height);
    }

    private static synchronized Identifier texture(TuneWeavePlatform platform, int rasterSize) {
        IconKey key = new IconKey(platform, rasterSize);
        Identifier cached = TEXTURES.get(key);
        if (cached != null) return cached;
        if (FAILED.contains(key)) return null;
        String path = "/assets/musichud_tuneweave/textures/platforms/" + platform.apiName() + ".svg";
        try (InputStream input = MusicHud.class.getResourceAsStream(path)) {
            if (input == null) return null;
            PNGTranscoder transcoder = new PNGTranscoder();
            transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) rasterSize);
            transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) rasterSize);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transcoder.transcode(new TranscoderInput(input), new TranscoderOutput(output));
            NativeImage image = NativeImage.read(new ByteArrayInputStream(output.toByteArray()));
            DynamicTexture texture = new FilteredDynamicTexture(
                    () -> "musichud_platform_" + platform.apiName() + '_' + rasterSize, image);
            Identifier id = Identifier.fromNamespaceAndPath(MusicHud.MOD_ID,
                    "platform/" + platform.apiName() + '_' + rasterSize);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            TEXTURES.put(key, id);
            return id;
        } catch (Exception error) {
            FAILED.add(key);
            MusicHud.getLogger(PlatformIconRenderer.class).warn("Failed to load HUD platform icon {}", platform.apiName(), error);
            return null;
        }
    }

    private record IconKey(TuneWeavePlatform platform, int pixelSize) {
    }

    private static final class FilteredDynamicTexture extends DynamicTexture {
        private FilteredDynamicTexture(Supplier<String> name, NativeImage image) {
            super(name, image);
            sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        }
    }
}
