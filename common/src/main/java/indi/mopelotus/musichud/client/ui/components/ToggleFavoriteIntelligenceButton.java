package indi.mopelotus.musichud.client.ui.components;

import icyllis.modernui.core.Context;
import indi.mopelotus.musichud.MusicHud;
import indi.mopelotus.musichud.beans.music.Playlist;
import indi.mopelotus.musichud.client.services.music.MusicService;
import indi.mopelotus.musichud.client.utils.image.ImageUtils;
import net.minecraft.client.resources.language.I18n;
import java.util.concurrent.CompletableFuture;

public final class ToggleFavoriteIntelligenceButton extends AsyncStateToggleButton {
    private final MusicService musicService = MusicService.getInstance();
    public ToggleFavoriteIntelligenceButton(Context context) {
        super(context, new Appearance(
                () -> I18n.get(MusicHud.MOD_ID + ".button.disableFavoriteIntelligence"),
                () -> I18n.get(MusicHud.MOD_ID + ".button.enableFavoriteIntelligence"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/heart_filled.png"),
                () -> ImageUtils.getImageFromResource("/assets/musichud_tuneweave/textures/gui/icons/heart.png")));
    }
    public void bindPlaylist(Playlist playlist) {
        if (playlist == null) { bindAsync(null, null, null); return; }
        bindAsync(() -> CompletableFuture.completedFuture(musicService.isFavoriteIntelligenceEnabled(playlist)),
                selected -> musicService.setFavoriteIntelligenceEnabled(playlist, selected),
                listener -> musicService.onFavoriteIntelligenceStateChange(enabled ->
                        listener.accept(enabled && musicService.isFavoriteIntelligenceEnabled(playlist))));
    }
}