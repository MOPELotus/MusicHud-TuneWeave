package indi.mopelotus.musichud.client.ui.hud.pipelines;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import indi.mopelotus.musichud.MusicHud;
import net.minecraft.resources.Identifier;

public class HudRenderPipelines {
    public static final RenderPipeline.Snippet MATRICES_PROJECTION_SNIPPET =
            RenderPipeline.builder()
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .buildSnippet();

    public static final RenderPipeline BACKGROUND;

    public static final RenderPipeline ROUNDED_ALBUM;

    public static final RenderPipeline PROGRESS_BAR;

    private static BindGroupLayout.Builder matrices() {
        return BindGroupLayout.builder()
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER);
    }

    static {
        BACKGROUND = RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "pipeline/background"))
                .withVertexShader(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "core/background"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "core/background"))
                .withBindGroupLayout(matrices()
                        .withUniform("MHBasePosition", UniformType.UNIFORM_BUFFER)
                        .withUniform("MHNowPlayingThemeColor", UniformType.UNIFORM_BUFFER)
                        .withUniform("MHDynamicStatus", UniformType.UNIFORM_BUFFER)
                        .build())
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();
        ROUNDED_ALBUM = RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "pipeline/album_image"))
                .withVertexShader(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "core/album_image"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "core/album_image"))
                .withBindGroupLayout(matrices()
                        .withUniform("MHAlbumPosition", UniformType.UNIFORM_BUFFER)
                        .withUniform("MHDynamicStatus", UniformType.UNIFORM_BUFFER)
                        .withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("Sampler1", UniformType.COMBINED_IMAGE_SAMPLER)
                        .build())
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();
        PROGRESS_BAR = RenderPipeline.builder(MATRICES_PROJECTION_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "pipeline/progress_bar"))
                .withVertexShader(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "core/progress_bar"))
                .withFragmentShader(Identifier.fromNamespaceAndPath(MusicHud.MOD_ID, "core/progress_bar"))
                .withBindGroupLayout(matrices()
                        .withUniform("MHProgressPosition", UniformType.UNIFORM_BUFFER)
                        .withUniform("MHProgressStyle", UniformType.UNIFORM_BUFFER)
                        .withUniform("MHDynamicStatus", UniformType.UNIFORM_BUFFER)
                        .build())
                .withVertexBinding(0, DefaultVertexFormat.POSITION_COLOR)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .build();
    }
}
