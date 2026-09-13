package indi.mopelotus.musichud.client.ui.hud.pipelines;

import indi.mopelotus.musichud.MusicHud;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public class HudRenderPipelines {
    // Note: ProjMat and ModelViewMat are plain uniforms from moj_import (not UBOs in 1.21.1)
    public static HudShaderProgram background() { return HudShaderManager.getOrCreate(
            ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID, "shaders/core/background.vsh"),
            ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID, "shaders/core/background.fsh"),
            List.of("MHBasePosition", "MHNowPlayingThemeColor", "MHDynamicStatus")
    ); }

    public static HudShaderProgram roundedAlbum() { return HudShaderManager.getOrCreate(
            ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID, "shaders/core/album_image.vsh"),
            ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID, "shaders/core/album_image.fsh"),
            List.of("MHAlbumPosition", "MHDynamicStatus")
    ); }

    public static HudShaderProgram progressBar() { return HudShaderManager.getOrCreate(
            ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID, "shaders/core/progress_bar.vsh"),
            ResourceLocation.fromNamespaceAndPath(MusicHud.MOD_ID, "shaders/core/progress_bar.fsh"),
            List.of("MHProgressPosition", "MHProgressStyle", "MHDynamicStatus")
    ); }
}
